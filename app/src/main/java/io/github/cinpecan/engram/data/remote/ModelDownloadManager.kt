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
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * Resumable HTTP Range downloader backed by [RandomAccessFile] and streaming SHA-256 verification.
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
                updates[model.id] = ModelDownloadState.Completed(
                    localDirPath = dir.absolutePath,
                    verifiedSha256 = model.sha256,
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
            dir.exists() && model.files.all { file -> File(dir, file.name).exists() }
        }
    }

    fun startOrResume(model: ModelDescriptorDto) {
        if (activeJobs[model.id]?.isActive == true) return
        val job = scope.launch {
            val targetDir = resolveModelDirectory(model.id)
            targetDir.mkdirs()

            val totalBytes = model.files.sumOf { it.sizeBytes }.coerceAtLeast(model.sizeBytes)
            var cumulativeDone = 0L

            try {
                var lastDigestHex = ""
                for (fileSpec in model.files) {
                    val finalFile = File(targetDir, fileSpec.name)
                    val alreadyComplete = finalFile.exists() && (
                        (fileSpec.sha256 == UNPINNED_SHA256 && finalFile.length() > 0L) ||
                            finalFile.length() == fileSpec.sizeBytes
                        )
                    if (alreadyComplete) {
                        cumulativeDone += finalFile.length()
                        _downloadStates.update {
                            it + (model.id to ModelDownloadState.Downloading(cumulativeDone.coerceAtMost(totalBytes), totalBytes, fileSpec.name))
                        }
                        lastDigestHex = computeSha256(finalFile)
                        continue
                    }

                    val partFile = File(targetDir, "${fileSpec.name}.part")
                    val existingBytes = if (partFile.exists()) partFile.length() else 0L
                    val requestBuilder = Request.Builder().url(fileSpec.url)
                    if (existingBytes > 0L) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    val call = okHttpClient.newCall(requestBuilder.build())
                    activeCalls[model.id] = call

                    call.execute().use { response ->
                        if (!response.isSuccessful && response.code != 206) {
                            error("HTTP ${response.code} downloading ${fileSpec.name}")
                        }
                        val appendMode = response.code == 206 && existingBytes > 0L
                        val body = response.body ?: error("Empty body for ${fileSpec.name}")
                        val buffer = ByteArray(16 * 1024)
                        var fileWritten = if (appendMode) existingBytes else 0L

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
                                    _downloadStates.update {
                                        it + (
                                            model.id to ModelDownloadState.Downloading(
                                                bytesRead = (cumulativeDone + fileWritten).coerceAtMost(totalBytes),
                                                totalBytes = totalBytes,
                                                currentFileName = fileSpec.name,
                                            )
                                            )
                                    }
                                }
                            }
                        }
                        if (!isActive) {
                            _downloadStates.update {
                                it + (model.id to ModelDownloadState.Paused(cumulativeDone + fileWritten, totalBytes))
                            }
                            return@launch
                        }
                    }

                    val digest = computeSha256(partFile)
                    if (fileSpec.sha256 != UNPINNED_SHA256 && digest != fileSpec.sha256) {
                        partFile.delete()
                        error("SHA-256 mismatch for ${fileSpec.name}")
                    }
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }
                    lastDigestHex = digest
                    cumulativeDone += finalFile.length()
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
        private val UNPINNED_SHA256 = "0".repeat(64)

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
                sha256 = UNPINNED_SHA256,
                description = "轻量端侧推理首选，支持离线结构化提取与 RAG 问答。",
                files = listOf(
                    ModelFileDto("config.json", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/config.json", 624L, UNPINNED_SHA256),
                    ModelFileDto("llm_config.json", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm_config.json", 780L, UNPINNED_SHA256),
                    ModelFileDto("tokenizer.txt", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/tokenizer.txt", 2_450_000L, UNPINNED_SHA256),
                    ModelFileDto("llm.mnn", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn", 4_200_000L, UNPINNED_SHA256),
                    ModelFileDto("llm.mnn.weight", "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight", 391_807_476L, UNPINNED_SHA256),
                ),
            ),
            ModelDescriptorDto(
                id = "qwen2.5-1.5b-instruct-mnn",
                name = "Qwen2.5-1.5B Instruct (MNN INT4)",
                role = "generation",
                quantization = "INT4",
                sizeBytes = 1_027_604_480L,
                minRamMb = 4096,
                downloadUrl = "https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/llm.mnn.weight",
                mirrorUrl = "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                sha256 = UNPINNED_SHA256,
                description = "主力高质量端侧模型，适合复杂关系脉络抽取。",
                files = listOf(
                    ModelFileDto("config.json", "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/config.json", 640L, UNPINNED_SHA256),
                    ModelFileDto("llm_config.json", "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm_config.json", 812L, UNPINNED_SHA256),
                    ModelFileDto("tokenizer.txt", "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/tokenizer.txt", 2_450_000L, UNPINNED_SHA256),
                    ModelFileDto("llm.mnn", "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn", 6_153_028L, UNPINNED_SHA256),
                    ModelFileDto("llm.mnn.weight", "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn.weight", 1_019_000_000L, UNPINNED_SHA256),
                ),
            ),
            ModelDescriptorDto(
                id = "gte-multilingual-base-mnn",
                name = "GTE Multilingual Base (MNN Embedding)",
                role = "embedding",
                quantization = "INT8",
                sizeBytes = 167_772_160L,
                minRamMb = 1024,
                downloadUrl = "https://huggingface.co/taobao-mnn/gte_sentence-embedding_multilingual-base-MNN/resolve/main/model.mnn.weight",
                mirrorUrl = "https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn.weight",
                sha256 = UNPINNED_SHA256,
                description = "中英双语本地句向量模型，支撑本地混合 RAG 语义召回。",
                files = listOf(
                    ModelFileDto("model.mnn", "https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn", 1_772_160L, UNPINNED_SHA256),
                    ModelFileDto("model.mnn.weight", "https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn.weight", 166_000_000L, UNPINNED_SHA256),
                ),
            ),
        )
    }
}
