package com.abdownloadmanager.desktop.pages.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Uses yt-dlp solely for URL extraction and format discovery.
 * All actual downloading is handled by the app's own HTTP engine.
 */
class YtDlpService {

    private val json = Json { ignoreUnknownKeys = true }

    fun isAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("yt-dlp", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor(5, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchVideoInfo(url: String): Result<YouTubeVideoInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val output = runCommand("yt-dlp", "--dump-json", "--no-download", "--no-warnings", url)
            val raw = json.decodeFromString<YtDlpRawVideoInfo>(output)

            val formats = raw.formats
                .filter { it.ext != "mhtml" } // skip storyboards
                .map { f ->
                    YouTubeFormat(
                        formatId = f.formatId,
                        ext = f.ext,
                        quality = f.formatNote ?: f.resolution ?: "unknown",
                        width = f.width ?: 0,
                        height = f.height ?: 0,
                        fps = f.fps?.toInt() ?: 0,
                        vcodec = f.vcodec ?: "none",
                        acodec = f.acodec ?: "none",
                        filesize = f.filesize ?: f.filesizeApprox ?: 0L,
                    )
                }

            YouTubeVideoInfo(
                title = raw.title,
                uploader = raw.uploader,
                duration = raw.duration ?: 0.0,
                formats = formats,
            )
        }
    }

    /**
     * Extract direct download URLs for a format.
     * For video-only formats with needsMerge=true, also extracts the best audio URL.
     * Returns video URL, optional audio URL, and the expected filename.
     */
    suspend fun extractUrls(
        url: String,
        videoFormatId: String,
        needsMerge: Boolean,
        rawFormatId: String? = null,
    ): Result<ExtractedUrls> = withContext(Dispatchers.IO) {
        runCatching {
            // For merge: use raw format ID with +bestaudio (selectors can't combine with +)
            // For single: use the format selector directly
            val formatSpec = if (needsMerge) {
                "${rawFormatId ?: videoFormatId}+bestaudio"
            } else videoFormatId

            val urlOutput = runCommand(
                "yt-dlp", "-f", formatSpec, "--get-url", "--no-warnings", url
            )
            val urls = urlOutput.trim().lines().filter { it.isNotBlank() }

            // Get filename based on video format only (so extension matches selection)
            val nameOutput = runCommand(
                "yt-dlp", "-f", videoFormatId, "--get-filename",
                "-o", "%(title)s.%(ext)s", "--no-warnings", url
            )
            val filename = nameOutput.trim()

            if (needsMerge && urls.size >= 2) {
                ExtractedUrls(videoUrl = urls[0], audioUrl = urls[1], filename = filename)
            } else {
                ExtractedUrls(videoUrl = urls.first(), audioUrl = null, filename = filename)
            }
        }
    }

    private fun runCommand(vararg cmd: String): String {
        val process = ProcessBuilder(*cmd)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("${cmd.first()} timed out")
        }
        if (process.exitValue() != 0) {
            error("${cmd.first()} failed: $stderr")
        }
        return stdout
    }

    data class ExtractedUrls(
        val videoUrl: String,
        val audioUrl: String?,
        val filename: String,
    )
}

// yt-dlp JSON models (internal)
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@kotlinx.serialization.json.JsonIgnoreUnknownKeys
@Serializable
internal data class YtDlpRawVideoInfo(
    val title: String = "",
    val uploader: String? = null,
    val duration: Double? = null,
    val formats: List<YtDlpRawFormat> = emptyList(),
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@kotlinx.serialization.json.JsonIgnoreUnknownKeys
@Serializable
internal data class YtDlpRawFormat(
    @SerialName("format_id") val formatId: String = "",
    val ext: String = "",
    val resolution: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
)

// Public models
data class YouTubeVideoInfo(
    val title: String,
    val uploader: String?,
    val duration: Double,
    val formats: List<YouTubeFormat>,
)

data class YouTubeFormat(
    val formatId: String,
    val ext: String,
    val quality: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val vcodec: String,
    val acodec: String,
    val filesize: Long,
) {
    val hasVideo get() = vcodec != "none"
    val hasAudio get() = acodec != "none"
    val isVideoOnly get() = hasVideo && !hasAudio

    val displayResolution: String
        get() = when {
            !hasVideo -> "Audio only"
            quality.matches(Regex("""\d+p""")) -> quality
            height > 0 -> "${height}p"
            else -> quality
        }

    val displayCodec: String
        get() {
            val v = if (hasVideo) simplifyCodec(vcodec) else ""
            val a = if (hasAudio) simplifyCodec(acodec) else ""
            return when {
                v.isNotEmpty() && a.isNotEmpty() -> "$v + $a"
                v.isNotEmpty() -> v
                else -> a
            }
        }

    val displaySize: String
        get() = when {
            filesize <= 0 -> "?"
            filesize >= 1024 * 1024 * 1024 -> "%.1f GB".format(filesize / (1024.0 * 1024.0 * 1024.0))
            filesize >= 1024 * 1024 -> "%.1f MB".format(filesize / (1024.0 * 1024.0))
            filesize >= 1024 -> "%.1f KB".format(filesize / 1024.0)
            else -> "$filesize B"
        }

    val qualityLabel: String
        get() = buildString {
            append(displayResolution)
            if (hasVideo && fps > 30) append(" ${fps}fps")
            append(" ($ext)")
            if (isVideoOnly) append(" + audio")
            if (!hasVideo && hasAudio) append(" [audio]")
        }
}

enum class FormatFilter(val label: String) {
    BEST("Best quality"),
    COMBINED("Combined only"),
    AUDIO_ONLY("Audio only"),
    ALL("All formats"),
}

private fun simplifyCodec(codec: String): String = when {
    codec.startsWith("avc1") || codec.contains("h264", true) -> "H.264"
    codec.startsWith("av01") || codec.contains("av1", true) -> "AV1"
    codec.contains("vp9", true) -> "VP9"
    codec.contains("mp4a") || codec.contains("aac", true) -> "AAC"
    codec.contains("opus", true) -> "Opus"
    codec == "none" -> ""
    else -> codec.take(8)
}
