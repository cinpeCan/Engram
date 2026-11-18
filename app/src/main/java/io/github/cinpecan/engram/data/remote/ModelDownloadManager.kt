package io.github.cinpecan.engram.data.remote

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 * Resumable HTTP Range downloader fetching quantized MNN weights directly from open-source hosts
 * (ModelScope primary + HuggingFace automatic fallback), backed by [RandomAccessFile] and SHA-256 checks.
 */
class ModelDownloadManager internal constructor(
    private val primaryModelsDir: File,
    private val externalModelsDir: File?,
    private val okHttpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    initialCatalog: List<ModelDescriptorDto> = builtInCatalog(),
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
        initialCatalog = builtInCatalog(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _catalog = MutableStateFlow(initialCatalog)
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

                    val candidateUrls = listOfNotNull(fileSpec.url, fileSpec.fallbackUrl).distinct()
                    val digest = downloadFileWithMirrors(
                        modelId = model.id,
                        fileSpec = fileSpec,
                        candidateUrls = candidateUrls,
                        targetDir = targetDir,
                        finalFile = finalFile,
                        cumulativeDoneBeforeFile = cumulativeDone,
                        knownFileSizes = knownFileSizes,
                        totalKnownBytes = ::totalKnownBytes,
                        onPaused = { pausedRead ->
                            _downloadStates.update {
                                it + (
                                    model.id to ModelDownloadState.Paused(
                                        bytesRead = cumulativeDone + pausedRead,
                                        totalBytes = totalKnownBytes(),
                                    )
                                    )
                            }
                        },
                    ) ?: return@launch

                    lastDigestHex = digest
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

                _downloadStates.update {
                    it + (model.id to ModelDownloadState.Completed(targetDir.absolutePath, lastDigestHex))
                }
            } catch (e: CancellationException) {
                throw e
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

    private suspend fun downloadFileWithMirrors(
        modelId: String,
        fileSpec: ModelFileDto,
        candidateUrls: List<String>,
        targetDir: File,
        finalFile: File,
        cumulativeDoneBeforeFile: Long,
        knownFileSizes: MutableMap<String, Long>,
        totalKnownBytes: () -> Long,
        onPaused: (Long) -> Unit,
    ): String? {
        var lastError: Exception? = null
        val partFile = File(targetDir, "${fileSpec.name}.part")

        for (downloadUrl in candidateUrls) {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            var forceFresh = false
            try {
                while (true) {
                    val requestBuilder = Request.Builder().url(downloadUrl)
                    if (existingBytes > 0L && !forceFresh) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    val call = okHttpClient.newCall(requestBuilder.build())
                    activeCalls[modelId] = call

                    var retryFresh = false
                    var completedDigest: String? = null
                    call.execute().use { response ->
                        if (response.code == 416 && existingBytes > 0L) {
                            val rangeTotal = parseContentRangeTotal(response)
                            if (rangeTotal != null && existingBytes == rangeTotal) {
                                knownFileSizes[fileSpec.name] = rangeTotal
                                completedDigest = finalizeCompletedPart(partFile, finalFile, fileSpec).orEmpty()
                                return@use
                            }
                            partFile.delete()
                            existingBytes = 0L
                            forceFresh = true
                            retryFresh = true
                            return@use
                        }

                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("HTTP ${response.code} downloading ${fileSpec.name}")
                        }

                        val appendMode = response.code == 206 && existingBytes > 0L
                        val body = response.body ?: throw IOException("Empty body for ${fileSpec.name}")
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
                                while (kotlin.coroutines.coroutineContext.isActive) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    raf.write(buffer, 0, read)
                                    fileWritten += read
                                    if (lastEmittedBytes < 0L || fileWritten - lastEmittedBytes >= 64 * 1024) {
                                        lastEmittedBytes = fileWritten
                                        val totalNow = totalKnownBytes()
                                        _downloadStates.update {
                                            it + (
                                                modelId to ModelDownloadState.Downloading(
                                                    bytesRead = (cumulativeDoneBeforeFile + fileWritten).coerceAtMost(totalNow),
                                                    totalBytes = totalNow,
                                                    currentFileName = fileSpec.name,
                                                )
                                                )
                                        }
                                    }
                                }
                            }
                        }

                        if (!kotlin.coroutines.coroutineContext.isActive) {
                            onPaused(fileWritten)
                            return null
                        }

                        if (expectedFileBytes != null && expectedFileBytes > 0L && fileWritten != expectedFileBytes) {
                            throw IOException("文件 ${fileSpec.name} 下载未完整 ($fileWritten/$expectedFileBytes)")
                        }

                        completedDigest = finalizeCompletedPart(partFile, finalFile, fileSpec).orEmpty()
                    }

                    if (completedDigest != null) {
                        return completedDigest
                    }
                    if (!retryFresh) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_downloadStates.value[modelId] is ModelDownloadState.Paused) {
                    return null
                }
                lastError = e
            }
        }
        throw lastError ?: IOException("下载失败：${fileSpec.name}")
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
        val fallbackBaseUrl = fileSpec.fallbackUrl?.substringBeforeLast('/')
        pendingSpecs.add(
            ModelFileDto(
                name = embedFile,
                url = "$baseUrl/$embedFile",
                sizeBytes = 1L,
                sha256 = null,
                fallbackUrl = fallbackBaseUrl?.let { "$it/$embedFile" },
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
        private const val MODELSCOPE_BASE =
            "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master"
        private const val HUGGINGFACE_BASE =
            "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main"

        fun builtInCatalog(): List<ModelDescriptorDto> = listOf(
            ModelDescriptorDto(
                id = "qwen2.5-0.5b-instruct-mnn",
                name = "Qwen2.5-0.5B Instruct",
                role = "generation",
                quantization = "INT4",
                sizeBytes = 542_845_540L,
                minRamMb = 2048,
                downloadUrl = "$MODELSCOPE_BASE/llm.mnn.weight",
                mirrorUrl = "$HUGGINGFACE_BASE/llm.mnn.weight",
                sha256 = "8a31f5a708797bf5bcaac009dc8a6a7b724ec2cabe93fe4687833d255cf443d5",
                description = null,
                files = listOf(
                    ModelFileDto(
                        name = "config.json",
                        url = "$MODELSCOPE_BASE/config.json",
                        sizeBytes = 200L,
                        sha256 = null,
                        fallbackUrl = "$HUGGINGFACE_BASE/config.json",
                    ),
                    ModelFileDto(
                        name = "llm_config.json",
                        url = "$MODELSCOPE_BASE/llm_config.json",
                        sizeBytes = 2_903L,
                        sha256 = null,
                        fallbackUrl = "$HUGGINGFACE_BASE/llm_config.json",
                    ),
                    ModelFileDto(
                        name = "tokenizer.mtok",
                        url = "$MODELSCOPE_BASE/tokenizer.mtok",
                        sizeBytes = 4_109_683L,
                        sha256 = "c57d63b4ec886c35ba195879a6a6827b29f6bc48635d49dd256ade69f170ca99",
                        fallbackUrl = "$HUGGINGFACE_BASE/tokenizer.mtok",
                    ),
                    ModelFileDto(
                        name = "llm.mnn",
                        url = "$MODELSCOPE_BASE/llm.mnn",
                        sizeBytes = 681_000L,
                        sha256 = "40f8f278e67d47d1b76ccef17e77510729bc0df551bdbaca350d4e6f69e8c0ea",
                        fallbackUrl = "$HUGGINGFACE_BASE/llm.mnn",
                    ),
                    ModelFileDto(
                        name = "embeddings_bf16.bin",
                        url = "$MODELSCOPE_BASE/embeddings_bf16.bin",
                        sizeBytes = 272_269_312L,
                        sha256 = "4e96b0df6d274768cbb7e72404011853d23349999b658dc2f4dfb3c431ea223f",
                        fallbackUrl = "$HUGGINGFACE_BASE/embeddings_bf16.bin",
                    ),
                    ModelFileDto(
                        name = "llm.mnn.weight",
                        url = "$MODELSCOPE_BASE/llm.mnn.weight",
                        sizeBytes = 262_882_442L,
                        sha256 = "8a31f5a708797bf5bcaac009dc8a6a7b724ec2cabe93fe4687833d255cf443d5",
                        fallbackUrl = "$HUGGINGFACE_BASE/llm.mnn.weight",
                    ),
                ),
            ),
        )
    }
}
