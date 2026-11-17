package io.github.cinpecan.engram.data.remote

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long,
        val currentFileName: String,
    ) : ModelDownloadState {
        val progress: Float
            get() = if (totalBytes > 0L) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }

    data class Paused(val bytesRead: Long, val totalBytes: Long) : ModelDownloadState
    data class Completed(val localDirPath: String, val verifiedSha256: String) : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Resumable HTTP Range downloader backed by [RandomAccessFile], header-calibrated byte progress,
 * and SHA-256 verification with `.sha256` completion markers.
 */
class ModelDownloadManager internal constructor(
    private val primaryModelsDir: File,
    private val externalModelsDir: File?,
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(
        primaryModelsDir = File(context.applicationContext.filesDir, "models"),
        externalModelsDir = context.applicationContext.getExternalFilesDir(null)?.let { File(it, "models") },
        okHttpClient = okHttpClient,
        json = json,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _catalog = MutableStateFlow(builtInCatalog())
    val catalog: StateFlow<List<ModelDescriptorDto>> = _catalog.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    init {
        refreshLocalModelStates()
    }

    fun refreshLocalModelStates() {
        val updates = HashMap<String, ModelDownloadState>()
        for (model in _catalog.value) {
            val dir = resolveExistingModelDir(model)
            if (dir != null) {
                val mainFile = model.files.lastOrNull()?.name ?: "llm.mnn.weight"
                val marker = File(dir, "$mainFile.sha256")
                val digest = runCatching { marker.takeIf { it.exists() }?.readText()?.trim() }
                    .getOrNull()
                    .orEmpty()
                updates[model.id] = ModelDownloadState.Completed(
                    localDirPath = dir.absolutePath,
                    verifiedSha256 = digest,
                )
            }
        }
        if (updates.isNotEmpty()) {
            _downloadStates.update { current -> current + updates }
        }
    }

    suspend fun fetchCatalog(serverBaseUrl: String): Result<List<ModelDescriptorDto>> =
        runCatching {
            val api = ManifestApi.create(serverBaseUrl, okHttpClient, json)
            val response = api.getModelManifest()
            _catalog.value = response.models
            refreshLocalModelStates()
            response.models
        }

    fun resolveModelDirectory(modelId: String): File =
        File(primaryModelsDir, modelId)

    fun resolveReadyModelDirPath(modelId: String): String? {
        val model = _catalog.value.firstOrNull { it.id == modelId } ?: return null
        return resolveExistingModelDir(model)?.absolutePath
    }

    private fun resolveExistingModelDir(model: ModelDescriptorDto): File? {
        val candidates = listOfNotNull(
            File(primaryModelsDir, model.id),
            externalModelsDir?.let { File(it, model.id) },
        )
        return candidates.firstOrNull { dir ->
            if (!dir.exists()) return@firstOrNull false
            if (!model.files.all { file -> isFileComplete(dir, file) }) return@firstOrNull false
            val embedFile = parseEmbeddingFileName(File(dir, "llm_config.json"))
            if (embedFile != null && model.files.none { it.name == embedFile }) {
                val target = File(dir, embedFile)
                val part = File(dir, "$embedFile.part")
                target.exists() && target.length() > 0L && !part.exists()
            } else {
                true
            }
        }
    }

    private fun isFileComplete(dir: File, spec: ModelFileDto): Boolean {
        val finalFile = File(dir, spec.name)
        val partFile = File(dir, "${spec.name}.part")
        val markerFile = File(dir, "${spec.name}.sha256")

        if (!finalFile.exists() || partFile.exists()) return false

        if (markerFile.exists()) {
            val recorded = runCatching { markerFile.readText().trim() }.getOrDefault("")
            if (recorded.length != 64 || finalFile.length() <= 0L) return false
            if (spec.sha256 != null && recorded != spec.sha256) return false
            return true
        }

        // Fallback verification for externally pushed bundles (`adb push`)
        if (spec.sha256 != null) {
            return computeSha256(finalFile) == spec.sha256
        }
        return if (spec.name.endsWith(".json", ignoreCase = true)) {
            finalFile.length() > 0L
        } else {
            val minExpected = (spec.sizeBytes * 0.9).toLong().coerceAtLeast(1L)
            finalFile.length() >= minExpected
        }
    }

    fun startOrResume(model: ModelDescriptorDto) {
        if (activeJobs[model.id]?.isActive == true) return
        val job = scope.launch {
            val targetDir = resolveModelDirectory(model.id)
            targetDir.mkdirs()

            val pendingSpecs = model.files.toMutableList()
            val knownFileSizes = model.files.associate { it.name to it.sizeBytes }.toMutableMap()
            var cumulativeDone = 0L

            fun totalKnownBytes(): Long =
                knownFileSizes.values.sum().coerceAtLeast(model.sizeBytes).coerceAtLeast(1L)

            try {
                var lastDigestHex = ""
                var idx = 0
                while (idx < pendingSpecs.size) {
                    val fileSpec = pendingSpecs[idx]
                    idx++

                    val finalFile = File(targetDir, fileSpec.name)
                    if (isFileComplete(targetDir, fileSpec)) {
                        val len = finalFile.length()
                        knownFileSizes[fileSpec.name] = len
                        cumulativeDone += len
                        _downloadStates.update {
                            it + (
                                model.id to ModelDownloadState.Downloading(
                                    bytesRead = cumulativeDone.coerceAtMost(totalKnownBytes()),
                                    totalBytes = totalKnownBytes(),
                                    currentFileName = fileSpec.name,
                                )
                                )
                        }
                        val markerFile = File(targetDir, "${fileSpec.name}.sha256")
                        lastDigestHex = if (markerFile.exists()) {
                            markerFile.readText().trim()
                        } else {
                            computeSha256(finalFile).also { markerFile.writeText(it) }
                        }
                        enqueueEmbeddingIfDeclared(fileSpec, finalFile, pendingSpecs, knownFileSizes)
                        continue
                    }

                    val partFile = File(targetDir, "${fileSpec.name}.part")
                    var existingBytes = if (partFile.exists()) partFile.length() else 0L
                    var forceFresh = false

                    while (true) {
                        val requestBuilder = Request.Builder().url(fileSpec.url)
                        if (existingBytes > 0L && !forceFresh) {
                            requestBuilder.header("Range", "bytes=$existingBytes-")
                        }

                        val call = okHttpClient.newCall(requestBuilder.build())
                        activeCalls[model.id] = call

                        var retryFresh = false
                        call.execute().use { response ->
                            if (response.code == 416 && existingBytes > 0L) {
                                val rangeTotal = parseContentRangeTotal(response)
                                if (rangeTotal != null && existingBytes == rangeTotal) {
                                    knownFileSizes[fileSpec.name] = rangeTotal
                                    finalizeCompletedPart(partFile, finalFile, fileSpec)?.let { lastDigestHex = it }
                                    cumulativeDone += finalFile.length()
                                    enqueueEmbeddingIfDeclared(fileSpec, finalFile, pendingSpecs, knownFileSizes)
                                    break
                                }
                                partFile.delete()
                                existingBytes = 0L
                                forceFresh = true
                                retryFresh = true
                                return@use
                            }

                            if (!response.isSuccessful && response.code != 206) {
                                error("HTTP ${response.code} downloading ${fileSpec.name}")
                            }

                            val appendMode = response.code == 206 && existingBytes > 0L
                            val body = response.body ?: error("Empty body for ${fileSpec.name}")
                            val expectedFileBytes = resolveExpectedFileBytes(
                                response = response,
                                appendMode = appendMode,
                                existingBytes = existingBytes,
                                bodyContentLength = body.contentLength(),
                            )
                            if (expectedFileBytes != null && expectedFileBytes > 0L) {
                                knownFileSizes[fileSpec.name] = expectedFileBytes
                            }

                            val buffer = ByteArray(16 * 1024)
                            var fileWritten = if (appendMode) existingBytes else 0L
                            var lastEmittedBytes = -1L

                            RandomAccessFile(partFile, "rw").use { raf ->
                                if (appendMode) {
                                    raf.seek(existingBytes)
                                } else {
                                    raf.setLength(0L)
                                }
                                body.byteStream().use { input ->
                                    while (isActive) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        raf.write(buffer, 0, read)
                                        fileWritten += read
                                        if (lastEmittedBytes < 0L || fileWritten - lastEmittedBytes >= 64 * 1024) {
                                            lastEmittedBytes = fileWritten
                                            val totalNow = totalKnownBytes()
                                            _downloadStates.update {
                                                it + (
                                                    model.id to ModelDownloadState.Downloading(
                                                        bytesRead = (cumulativeDone + fileWritten).coerceAtMost(totalNow),
                                                        totalBytes = totalNow,
                                                        currentFileName = fileSpec.name,
                                                    )
                                                    )
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isActive) {
                                _downloadStates.update {
                                    it + (
                                        model.id to ModelDownloadState.Paused(
                                            bytesRead = cumulativeDone + fileWritten,
                                            totalBytes = totalKnownBytes(),
                                        )
                                        )
                                }
                                return@launch
                            }

                            if (expectedFileBytes != null && expectedFileBytes > 0L && fileWritten != expectedFileBytes) {
                                error("文件 ${fileSpec.name} 下载未完整 ($fileWritten/$expectedFileBytes)")
                            }

                            lastDigestHex = finalizeCompletedPart(partFile, finalFile, fileSpec)
                                ?: lastDigestHex
                            val totalNow = totalKnownBytes()
                            cumulativeDone += finalFile.length()
                            _downloadStates.update {
                                it + (
                                    model.id to ModelDownloadState.Downloading(
                                        bytesRead = cumulativeDone.coerceAtMost(totalNow),
                                        totalBytes = totalNow,
                                        currentFileName = fileSpec.name,
                                    )
                                    )
                            }
                            enqueueEmbeddingIfDeclared(fileSpec, finalFile, pendingSpecs, knownFileSizes)
                        }

                        if (!retryFresh) break
                    }
                }

                _downloadStates.update {
                    it + (model.id to ModelDownloadState.Completed(targetDir.absolutePath, lastDigestHex))
                }
            } catch (e: Exception) {
                val current = _downloadStates.value[model.id]
                if (current !is ModelDownloadState.Paused) {
                    _downloadStates.update {
                        it + (model.id to ModelDownloadState.Failed(e.message ?: "下载失败"))
                    }
                }
            } finally {
                activeCalls.remove(model.id)
                activeJobs.remove(model.id)
            }
        }
        activeJobs[model.id] = job
    }

    fun pause(modelId: String) {
        val current = _downloadStates.value[modelId]
        if (current is ModelDownloadState.Downloading) {
            _downloadStates.update {
                it + (modelId to ModelDownloadState.Paused(current.bytesRead, current.totalBytes))
            }
        }
        activeCalls.remove(modelId)?.cancel()
        activeJobs.remove(modelId)?.cancel()
    }

    private fun finalizeCompletedPart(
        partFile: File,
        finalFile: File,
        fileSpec: ModelFileDto,
    ): String? {
        val digest = computeSha256(partFile)
        if (fileSpec.sha256 != null && digest != fileSpec.sha256) {
            partFile.delete()
            error("SHA-256 校验失败：${fileSpec.name}")
        }
        if (finalFile.exists()) {
            finalFile.delete()
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        File(finalFile.parentFile, "${fileSpec.name}.sha256").writeText(digest)
        return digest
    }

    private fun enqueueEmbeddingIfDeclared(
        fileSpec: ModelFileDto,
        finalFile: File,
        pendingSpecs: MutableList<ModelFileDto>,
        knownFileSizes: MutableMap<String, Long>,
    ) {
        if (fileSpec.name != "llm_config.json") return
        val embedFile = parseEmbeddingFileName(finalFile) ?: return
        if (pendingSpecs.any { it.name == embedFile }) return
        val baseUrl = fileSpec.url.substringBeforeLast('/')
        pendingSpecs.add(
            ModelFileDto(
                name = embedFile,
                url = "$baseUrl/$embedFile",
                sizeBytes = 1L,
                sha256 = null,
            ),
        )
        knownFileSizes[embedFile] = 1L
    }

    private fun parseEmbeddingFileName(llmConfigFile: File): String? {
        if (!llmConfigFile.exists()) return null
        return runCatching {
            val obj = json.parseToJsonElement(llmConfigFile.readText()).jsonObject
            obj["embedding_file"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun resolveExpectedFileBytes(
        response: Response,
        appendMode: Boolean,
        existingBytes: Long,
        bodyContentLength: Long,
    ): Long? {
        if (response.code == 206) {
            parseContentRangeTotal(response)?.let { return it }
            return if (bodyContentLength > 0L) existingBytes + bodyContentLength else null
        }
        return if (!appendMode && bodyContentLength > 0L) bodyContentLength else null
    }

    private fun parseContentRangeTotal(response: Response): Long? {
        val header = response.header("Content-Range") ?: return null
        val totalPart = header.substringAfterLast('/', missingDelimiterValue = "").trim()
        return totalPart.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun builtInCatalog(): List<ModelDescriptorDto> = listOf(
            ModelDescriptorDto(
                id = "qwen2.5-0.5b-instruct-mnn",
                name = "Qwen2.5-0.5B Instruct (MNN INT4)",
                role = "generation",
                quantization = "INT4",
                sizeBytes = 398_458_880L,
                minRamMb = 2048,
                downloadUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main/llm.mnn.weight",
                mirrorUrl = "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                sha256 = null,
                description = "端侧离线推理核心模型，支持流式结构化摘要、日程提议与 2D 关系脉络抽取。",
                files = listOf(
                    ModelFileDto("config.json", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/config.json", 624L, null),
                    ModelFileDto("llm_config.json", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm_config.json", 780L, null),
                    ModelFileDto("tokenizer.txt", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/tokenizer.txt", 2_450_000L, null),
                    ModelFileDto("llm.mnn", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn", 4_200_000L, null),
                    ModelFileDto("llm.mnn.weight", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight", 391_807_476L, null),
                ),
            ),
        )
    }
}
