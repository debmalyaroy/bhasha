// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** ASR is a Whisper model for any language; SPEECH_PACK a recogniser for one language. */
enum class ModelKind { ASR, VAD, LLM, SPEECH_PACK, TRANSLATOR }

data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    val title: String,
    val description: String,
    val url: String,
    val fileName: String,
    val bytes: Long,
    /** ASR only: whether the model can do speech -> English itself ("direct" mode). */
    val canTranslate: Boolean = false,
    val recommended: Boolean = false,
    /** SPEECH_PACK only: the language it recognises. */
    val lang: String? = null,
)

/** Models the app knows how to run. All are fetched from Hugging Face on demand; nothing ships in the APK. */
object ModelCatalog {
    private const val WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val vad = ModelSpec(
        id = "silero-vad",
        kind = ModelKind.VAD,
        title = "Silero VAD 5.1",
        description = "Detects when someone is speaking so only speech is transcribed.",
        url = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
        fileName = "ggml-silero-v5.1.2.bin",
        bytes = 885_098L,
    )

    // AI4Bharat IndicConformer (MIT), one model per language, exported for sherpa-onnx.
    // On 30 FLEURS test clips per language it gets 11-14 % of words wrong in Bengali
    // and Hindi and 21 % in Kannada; Whisper large-v3-turbo gets 92 % wrong in Bengali.
    private const val CONFORMER = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main"
    const val PACK_FILE_PATTERN = "indic-conformer-{lang}.int8.onnx"
    const val PACK_TOKENS_FILE = "indic-conformer-tokens.txt"   // bundled in the APK (assets/asr)

    private fun pack(lang: String, name: String, bytes: Long) = ModelSpec(
        id = "indic-conformer-$lang",
        kind = ModelKind.SPEECH_PACK,
        title = "$name speech",
        description = "IndicConformer by AI4Bharat, trained for $name. Far more accurate than Whisper " +
            "for this language and several times faster.",
        url = "$CONFORMER/$lang/model.int8.onnx",
        fileName = PACK_FILE_PATTERN.replace("{lang}", lang),
        bytes = bytes,
        recommended = true,
        lang = lang,
    )

    val packs = listOf(
        pack("hi", "Hindi", 197_595_593L),
        pack("bn", "Bengali", 197_595_578L),
        pack("kn", "Kannada", 197_595_728L),
    )

    val asr = listOf(
        ModelSpec(
            id = "whisper-large-v3-turbo-q5",
            kind = ModelKind.ASR,
            title = "Whisper large-v3-turbo",
            description = "General model for all languages. Needed only for Auto-detect; a language " +
                "pack above is much more accurate, above all for Bengali.",
            url = "$WHISPER/ggml-large-v3-turbo-q5_0.bin",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            bytes = 574_041_195L,
        ),
        ModelSpec(
            id = "whisper-medium-q5",
            kind = ModelKind.ASR,
            title = "Whisper medium",
            description = "Can translate speech straight to English without an LLM (direct mode). " +
                "Slower than turbo and less accurate, especially for Kannada.",
            url = "$WHISPER/ggml-medium-q5_0.bin",
            fileName = "ggml-medium-q5_0.bin",
            bytes = 539_212_467L,
            canTranslate = true,
        ),
        ModelSpec(
            id = "whisper-small-q5",
            kind = ModelKind.ASR,
            title = "Whisper small",
            description = "Smallest and fastest; supports direct mode. Noticeably weaker on Bengali and Kannada.",
            url = "$WHISPER/ggml-small-q5_1.bin",
            fileName = "ggml-small-q5_1.bin",
            bytes = 190_085_487L,
            canTranslate = true,
        ),
        ModelSpec(
            id = "whisper-large-v3-q5",
            kind = ModelKind.ASR,
            title = "Whisper large-v3",
            description = "Highest accuracy and supports direct mode, but several times slower than turbo.",
            url = "$WHISPER/ggml-large-v3-q5_0.bin",
            fileName = "ggml-large-v3-q5_0.bin",
            bytes = 1_081_140_203L,
            canTranslate = true,
        ),
    )

    // IndicTrans2 by AI4Bharat (MIT), the distilled 200M Indic-to-English model with rotary
    // positions, exported to int8 ONNX and packed into one file by tools/make_mt_pack.py.
    // On 30 FLEURS sentences per language, fed with the recogniser's own output, it scores
    // chrF 60-62 against 53-59 for Gemma 4 E2B, at a twelfth of the size and about 8x the speed.
    val translator = ModelSpec(
        id = "indictrans2-indic-en-200m",
        kind = ModelKind.TRANSLATOR,
        title = "IndicTrans2 translator",
        description = "Translation model by AI4Bharat for Hindi, Bengali and Kannada to English. " +
            "More accurate than Gemma 4 for translation, several times faster and far lighter on memory and battery.",
        url = "https://github.com/debmalyaroy/bhasha/releases/download/models-1/indictrans2-indic-en-200m-int8.bhmt",
        fileName = "indictrans2-indic-en-200m-int8.bhmt",
        bytes = 235_469_850L,
        recommended = true,
    )

    val llm = listOf(
        ModelSpec(
            id = "gemma-4-e2b-q4",
            kind = ModelKind.LLM,
            title = "Gemma 4 E2B",
            description = "Powers the tutor's explanations and conversation practice. Also translates " +
                "when the IndicTrans2 translator is not installed.",
            url = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf",
            fileName = "gemma-4-E2B-it-Q4_0.gguf",
            bytes = 2_841_481_184L,
            recommended = true,
        ),
        ModelSpec(
            id = "gemma-4-e4b-q4",
            kind = ModelKind.LLM,
            title = "Gemma 4 E4B",
            description = "Better translations and explanations, roughly half the speed. Wants 12 GB of RAM.",
            url = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf",
            fileName = "gemma-4-E4B-it-Q4_0.gguf",
            bytes = 4_590_807_392L,
        ),
    )

    val all: List<ModelSpec> = listOf(vad) + packs + translator + asr + llm

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState {
        val fraction: Float get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data object Installed : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Model files on disk plus their downloads. Downloads go through the system
 * DownloadManager, so multi-gigabyte files keep downloading (and resume after
 * network drops) while the app is in the background or killed.
 *
 * Files live in the app's external files dir, so they can also be side-loaded:
 *   adb push model.gguf /sdcard/Android/data/com.bhasha.translator/files/models/
 */
class ModelStore(private val context: Context, private val scope: CoroutineScope) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    val dir: File = (context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)).apply { mkdirs() }

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states
    private var poller: Job? = null

    init {
        refresh()
        if (activeIds().isNotEmpty()) startPolling()
    }

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)

    fun isInstalled(spec: ModelSpec): Boolean = _states.value[spec.id] == ModelState.Installed

    fun installed(kind: ModelKind): List<ModelSpec> = ModelCatalog.all.filter { it.kind == kind && isInstalled(it) }

    /** Anything that can turn speech into text: a language pack or a Whisper model. */
    fun hasSpeechModel(): Boolean =
        installed(ModelKind.SPEECH_PACK).isNotEmpty() || installed(ModelKind.ASR).isNotEmpty()

    /** Whether anything installed can turn text into English. */
    fun canTranslate(): Boolean = isInstalled(ModelCatalog.translator) || installed(ModelKind.LLM).isNotEmpty()

    /** Languages that have their own recogniser installed. */
    fun packLanguages(): Set<String> = installed(ModelKind.SPEECH_PACK).mapNotNull { it.lang }.toSet()

    /** Path pattern of the language packs, as the engine wants it. */
    val packPattern: String get() = File(dir, ModelCatalog.PACK_FILE_PATTERN).absolutePath

    /** The packs' token table, copied out of the APK on first use. Null if that fails. */
    fun packTokens(): File? {
        val out = File(dir, ModelCatalog.PACK_TOKENS_FILE)
        if (out.isFile && out.length() > 0) return out
        return runCatching {
            context.assets.open("asr/${ModelCatalog.PACK_TOKENS_FILE}").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out
        }.getOrNull()
    }

    fun download(spec: ModelSpec) {
        if (activeIds().containsKey(spec.id) || isOnDisk(spec)) return
        file(spec).delete()   // stale partial file from a failed attempt
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle(spec.title)
            .setDescription("Bhasha model download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, DIR, spec.fileName)
        val id = runCatching { dm.enqueue(request) }.getOrElse {
            update(spec.id, ModelState.Failed(it.message ?: "Could not start the download"))
            return
        }
        prefs.edit().putLong(spec.id, id).apply()
        update(spec.id, ModelState.Downloading(0, spec.bytes))
        startPolling()
    }

    /** Cancels a running download and removes the file. */
    fun delete(spec: ModelSpec) {
        activeIds()[spec.id]?.let { dm.remove(it) }
        prefs.edit().remove(spec.id).apply()
        file(spec).delete()
        update(spec.id, ModelState.Missing)
    }

    /** Re-reads the disk; call after side-loading files. */
    fun refresh() {
        val active = activeIds()
        _states.value = ModelCatalog.all.associate { spec ->
            spec.id to when {
                active.containsKey(spec.id) -> _states.value[spec.id] ?: ModelState.Downloading(0, spec.bytes)
                isOnDisk(spec) -> ModelState.Installed
                else -> (_states.value[spec.id] as? ModelState.Failed) ?: ModelState.Missing
            }
        }
    }

    private fun isOnDisk(spec: ModelSpec): Boolean = file(spec).let { it.isFile && it.length() == spec.bytes }

    private fun activeIds(): Map<String, Long> =
        ModelCatalog.all.mapNotNull { spec ->
            prefs.getLong(spec.id, -1L).takeIf { it >= 0 }?.let { spec.id to it }
        }.toMap()

    private fun update(id: String, state: ModelState) {
        _states.value = _states.value + (id to state)
    }

    private fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val active = activeIds()
                if (active.isEmpty()) break
                active.forEach { (modelId, downloadId) -> poll(modelId, downloadId) }
                delay(500)
            }
        }
    }

    private fun poll(modelId: String, downloadId: Long) {
        val spec = ModelCatalog.byId(modelId) ?: return
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use { c ->
            if (c == null || !c.moveToFirst()) {
                // The download vanished (cancelled from the notification, or already cleaned up).
                prefs.edit().remove(modelId).apply()
                update(modelId, if (isOnDisk(spec)) ModelState.Installed else ModelState.Missing)
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(modelId).apply()
                    if (isOnDisk(spec)) {
                        update(modelId, ModelState.Installed)
                    } else {
                        file(spec).delete()
                        update(modelId, ModelState.Failed("Downloaded file has the wrong size - please retry"))
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs.edit().remove(modelId).apply()
                    dm.remove(downloadId)
                    update(modelId, ModelState.Failed(failureText(reason)))
                }
                else -> update(modelId, ModelState.Downloading(soFar.coerceAtLeast(0), spec.bytes))
            }
        }
    }

    private fun failureText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free storage"
        DownloadManager.ERROR_CANNOT_RESUME -> "Download interrupted and could not resume"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Network error"
        DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR -> "Storage error"
        else -> "Download failed (code $reason)"
    }

    companion object {
        private const val DIR = "models"
    }
}
