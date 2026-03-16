package com.abdownloadmanager.desktop.pages.youtube

import com.abdownloadmanager.shared.util.DownloadSystem
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.contexts.ResumedBy
import ir.amirab.downloader.downloaditem.contexts.User
import ir.amirab.downloader.downloaditem.http.HttpDownloadItem
import ir.amirab.downloader.queue.DefaultQueueInfo
import ir.amirab.downloader.utils.OnDuplicateStrategy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Represents an active YouTube composite download.
 * Manages video + audio streams as a single logical download with combined progress.
 */
data class YouTubeCompositeDownload(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val folder: String,
    val filename: String,
    val videoDownloadId: Long = -1,
    val audioDownloadId: Long = -1,
    val videoFilesize: Long = 0,
    val audioFilesize: Long = 0,
)

sealed interface YouTubeDownloadState {
    val compositeId: String

    data class Downloading(
        override val compositeId: String,
        val videoProgress: Long,
        val audioProgress: Long,
        val totalSize: Long,
        val speed: Long,
        val percent: Int?,
        val videoName: String,
    ) : YouTubeDownloadState

    data class Merging(
        override val compositeId: String,
        val videoName: String,
        val percent: Int?,
    ) : YouTubeDownloadState

    data class Completed(
        override val compositeId: String,
        val filePath: String,
    ) : YouTubeDownloadState

    data class Error(
        override val compositeId: String,
        val message: String,
    ) : YouTubeDownloadState
}

/**
 * Manages YouTube composite downloads — coordinates video + audio downloads
 * through the app's own multi-connection HTTP engine, then merges with ffmpeg.
 */
class YouTubeDownloadManager(
    private val downloadSystem: DownloadSystem,
    private val scope: CoroutineScope,
) {
    private val _activeDownloads = MutableStateFlow<Map<String, YouTubeDownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, YouTubeDownloadState>> = _activeDownloads.asStateFlow()

    /**
     * Boot: scan for unfinished YouTube composite downloads from previous sessions.
     * Matches video-audio pairs by compositeId in the downloadPage field and
     * resumes monitoring/merging as needed.
     */
    fun boot() {
        scope.launch {
            // Wait for download system to fully load
            delay(3000)
            try {
                // Find all YouTube audio downloads that reference a video download ID
                val audioDownloads = downloadSystem.getDownloadItemsBy {
                    it.downloadPage?.startsWith("youtube-composite-audio:") == true
                }
                val debugLog = java.io.File("/tmp/yt_merge_debug.log")
                debugLog.appendText("BOOT: found ${audioDownloads.size} audio entries\n")

                for (audio in audioDownloads) {
                    // Extract video download ID from audio's downloadPage
                    // Format: "youtube-composite-audio:video=123" or legacy "youtube-composite-audio:uuid"
                    val dp = audio.downloadPage ?: continue
                    val videoIdStr = dp.removePrefix("youtube-composite-audio:")
                        .removePrefix("video=")
                    val videoId = videoIdStr.toLongOrNull() ?: continue

                    val video = downloadSystem.getDownloadItemById(videoId) ?: continue
                    debugLog.appendText("BOOT: matched audio=${audio.id}(${audio.status}) → video=${video.id}(${video.status})\n")

                    val compositeId = "boot-${audio.id}"
                    when {
                        // Both completed — merge now
                        video.status == DownloadStatus.Completed &&
                        audio.status == DownloadStatus.Completed -> {
                            scope.launch { performMerge(video.id, audio.id, compositeId) }
                        }
                        // One or both still downloading — monitor
                        else -> {
                            scope.launch { monitorAndMerge(video.id, audio.id, compositeId) }
                        }
                    }
                }
            } catch (_: Exception) {
                // Boot scan failed — not critical
            }
        }
    }

    private suspend fun performMerge(videoId: Long, audioId: Long, compositeId: String) {
        val debugLog = java.io.File("/tmp/yt_merge_debug.log")
        debugLog.appendText("MERGE: starting video=$videoId audio=$audioId composite=$compositeId\n")
        val videoItem = downloadSystem.getDownloadItemById(videoId) ?: run {
            debugLog.appendText("MERGE: video item not found\n"); return
        }
        val audioItem = downloadSystem.getDownloadItemById(audioId) ?: run {
            debugLog.appendText("MERGE: audio item not found\n"); return
        }

        val videoPath = "${videoItem.folder}/${videoItem.name}"
        val audioPath = "${audioItem.folder}/${audioItem.name}"

        // Check if audio file actually exists (might already be merged)
        if (!File(audioPath).exists()) return

        val ext = videoItem.name.substringAfterLast(".", "mp4")
        val mergedTmpFile = "${videoItem.folder}/.yt_merge_${compositeId.take(8)}.$ext"

        try {
            withContext(Dispatchers.IO) {
                mergeWithFfmpeg(videoPath, audioPath, mergedTmpFile)
            }
            withContext(Dispatchers.IO) {
                val merged = File(mergedTmpFile)
                val target = File(videoPath)
                target.delete()
                merged.copyTo(target, overwrite = true)
                merged.delete()
                File(audioPath).delete()
            }
            withContext(Dispatchers.IO) {
                try {
                    downloadSystem.removeDownload(audioId, false, EmptyContext)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { File(mergedTmpFile).delete() }
        }
    }

    private suspend fun monitorAndMerge(videoId: Long, audioId: Long, compositeId: String) {
        val debugLog = java.io.File("/tmp/yt_merge_debug.log")
        debugLog.appendText("MONITOR: waiting for video=$videoId audio=$audioId\n")
        coroutineScope {
            launch {
                // Skip wait if already completed
                val item = downloadSystem.getDownloadItemById(videoId)
                if (item?.status != DownloadStatus.Completed) {
                    debugLog.appendText("MONITOR: video $videoId not complete yet, waiting...\n")
                    downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(videoId)
                }
                debugLog.appendText("MONITOR: video $videoId done\n")
            }
            launch {
                val item = downloadSystem.getDownloadItemById(audioId)
                if (item?.status != DownloadStatus.Completed) {
                    debugLog.appendText("MONITOR: audio $audioId not complete yet, waiting...\n")
                    downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(audioId)
                }
                debugLog.appendText("MONITOR: audio $audioId done\n")
            }
        }
        debugLog.appendText("MONITOR: both done, calling performMerge\n")
        performMerge(videoId, audioId, compositeId)
    }

    /**
     * Start a YouTube composite download.
     * Creates two HTTP downloads (video + audio) through the app's download system,
     * monitors both, and merges when complete.
     */
    suspend fun startCompositeDownload(
        videoUrl: String,
        audioUrl: String,
        title: String,
        filename: String,
        folder: String,
        categoryId: Long?,
    ): String {
        val compositeId = java.util.UUID.randomUUID().toString()
        val ext = filename.substringAfterLast(".", "mp4")
        val baseName = filename.substringBeforeLast(".")

        val videoName = filename
        val audioName = ".yt_audio_${compositeId.take(8)}.$ext"

        // Create video download
        val videoId = downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = HttpDownloadItem(
                    link = videoUrl,
                    id = 0,
                    folder = folder,
                    name = videoName,
                    downloadPage = "youtube-composite:$compositeId",
                ),
                extraConfig = null,
                onDuplicateStrategy = OnDuplicateStrategy.AddNumbered,
                context = ResumedBy(User),
            ),
            queueId = DefaultQueueInfo.ID,
            categoryId = categoryId,
        )
        downloadSystem.userManualResume(videoId)

        // Create audio download (hidden name with dot prefix)
        val audioId = downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = HttpDownloadItem(
                    link = audioUrl,
                    id = 0,
                    folder = folder,
                    name = audioName,
                    downloadPage = "youtube-composite-audio:$compositeId",
                ),
                extraConfig = null,
                onDuplicateStrategy = OnDuplicateStrategy.OverrideDownload,
                context = ResumedBy(User),
            ),
            queueId = DefaultQueueInfo.ID,
            categoryId = null, // no category for audio temp file
        )
        downloadSystem.userManualResume(audioId)

        // Initialize state
        _activeDownloads.update {
            it + (compositeId to YouTubeDownloadState.Downloading(
                compositeId = compositeId,
                videoProgress = 0,
                audioProgress = 0,
                totalSize = 0,
                speed = 0,
                percent = 0,
                videoName = videoName,
            ))
        }

        // Start monitoring both downloads
        scope.launch {
            monitorAndMerge(
                compositeId = compositeId,
                videoId = videoId,
                audioId = audioId,
                videoName = videoName,
                audioName = audioName,
                folder = folder,
                filename = filename,
            )
        }

        return compositeId
    }

    private suspend fun monitorAndMerge(
        compositeId: String,
        videoId: Long,
        audioId: Long,
        videoName: String,
        audioName: String,
        folder: String,
        filename: String,
    ) {
        // Poll combined progress while waiting for both to finish
        coroutineScope {
            // Progress polling
            val progressJob = launch {
                while (isActive) {
                    val monitor = downloadSystem.downloadMonitor
                    val activeList = monitor.activeDownloadListFlow.value
                    val videoState = activeList.find { it.id == videoId }
                    val audioState = activeList.find { it.id == audioId }

                    val videoProgress = videoState?.progress ?: 0L
                    val audioProgress = audioState?.progress ?: 0L
                    val videoTotal = videoState?.contentLength?.takeIf { it > 0 } ?: 0L
                    val audioTotal = audioState?.contentLength?.takeIf { it > 0 } ?: 0L
                    val totalSize = videoTotal + audioTotal
                    val totalProgress = videoProgress + audioProgress
                    val speed = (videoState?.speed ?: 0L) + (audioState?.speed ?: 0L)
                    val percent = if (totalSize > 0) ((totalProgress * 100) / totalSize).toInt() else null

                    _activeDownloads.update {
                        it + (compositeId to YouTubeDownloadState.Downloading(
                            compositeId = compositeId,
                            videoProgress = videoProgress,
                            audioProgress = audioProgress,
                            totalSize = totalSize,
                            speed = speed,
                            percent = percent,
                            videoName = videoName,
                        ))
                    }
                    delay(500)
                }
            }

            // Wait for both downloads
            launch { downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(videoId) }
            launch { downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(audioId) }

            // Both done — stop polling
            progressJob.cancel()
        }

        // Check if both actually completed (not canceled)
        val videoItem = downloadSystem.getDownloadItemById(videoId)
        val audioItem = downloadSystem.getDownloadItemById(audioId)

        if (videoItem?.status != DownloadStatus.Completed || audioItem?.status != DownloadStatus.Completed) {
            _activeDownloads.update {
                it + (compositeId to YouTubeDownloadState.Error(
                    compositeId = compositeId,
                    message = "One or both downloads failed",
                ))
            }
            return
        }

        // Both completed — use ACTUAL paths from download system (may have _1 suffix from dedup)
        val videoPath = "${videoItem.folder}/${videoItem.name}"
        val audioPath = "${audioItem.folder}/${audioItem.name}"
        val ext = filename.substringAfterLast(".", "mp4")
        val mergedTmpFile = "$folder/.yt_merge_${compositeId.take(8)}.$ext"

        _activeDownloads.update {
            it + (compositeId to YouTubeDownloadState.Merging(
                compositeId = compositeId,
                videoName = videoName,
                percent = null,
            ))
        }

        try {
            withContext(Dispatchers.IO) {
                mergeWithFfmpeg(videoPath, audioPath, mergedTmpFile)
            }

            // Replace video with merged file, delete audio
            withContext(Dispatchers.IO) {
                val merged = File(mergedTmpFile)
                val target = File(videoPath)
                target.delete()
                merged.copyTo(target, overwrite = true)
                merged.delete()
                File(audioPath).delete()
            }

            // Remove audio download entry from the system
            withContext(Dispatchers.IO) {
                try {
                    downloadSystem.removeDownload(
                        id = audioId,
                        alsoRemoveFile = false, // we already deleted it in merge
                        context = EmptyContext,
                    )
                } catch (_: Exception) {}
            }

            _activeDownloads.update {
                it + (compositeId to YouTubeDownloadState.Completed(
                    compositeId = compositeId,
                    filePath = "$folder/$videoName",
                ))
            }

            // Clean up state after a delay
            delay(5000)
            _activeDownloads.update { it - compositeId }

        } catch (e: Exception) {
            // Clean up on failure
            withContext(Dispatchers.IO) {
                File(mergedTmpFile).delete()
            }
            _activeDownloads.update {
                it + (compositeId to YouTubeDownloadState.Error(
                    compositeId = compositeId,
                    message = "Merge failed: ${e.message}",
                ))
            }
        }
    }

    /**
     * Start audio download and merge for an existing video download.
     * Called when the video download was initiated through the normal Add Download dialog.
     */
    suspend fun startAudioAndMerge(
        videoDownloadId: Long,
        audioUrl: String,
        folder: String,
        filename: String,
    ) {
        val compositeId = java.util.UUID.randomUUID().toString()
        val audioName = ".yt_audio_${compositeId.take(8)}.webm"

        // Create audio download
        val audioId = downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = HttpDownloadItem(
                    link = audioUrl,
                    id = 0,
                    folder = folder,
                    name = audioName,
                    downloadPage = "youtube-composite-audio:video=$videoDownloadId",
                ),
                extraConfig = null,
                onDuplicateStrategy = OnDuplicateStrategy.OverrideDownload,
                context = ResumedBy(User),
            ),
            queueId = DefaultQueueInfo.ID,
            categoryId = null,
        )
        downloadSystem.userManualResume(audioId)

        // Wait for both video and audio to finish
        coroutineScope {
            launch { downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(videoDownloadId) }
            launch { downloadSystem.downloadMonitor.waitForDownloadToFinishOrCancel(audioId) }
        }

        val videoItem = downloadSystem.getDownloadItemById(videoDownloadId)
        val audioItem = downloadSystem.getDownloadItemById(audioId)

        if (videoItem?.status != DownloadStatus.Completed || audioItem?.status != DownloadStatus.Completed) {
            return
        }

        val videoPath = "${videoItem.folder}/${videoItem.name}"
        val audioPath = "${audioItem.folder}/${audioItem.name}"
        val ext = filename.substringAfterLast(".", "mp4")
        val mergedTmpFile = "$folder/.yt_merge_${compositeId.take(8)}.$ext"

        try {
            withContext(Dispatchers.IO) {
                mergeWithFfmpeg(videoPath, audioPath, mergedTmpFile)
            }
            withContext(Dispatchers.IO) {
                val merged = File(mergedTmpFile)
                val target = File(videoPath)
                target.delete()
                merged.copyTo(target, overwrite = true)
                merged.delete()
                File(audioPath).delete()
            }
            withContext(Dispatchers.IO) {
                try {
                    downloadSystem.removeDownload(audioId, false, EmptyContext)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { File(mergedTmpFile).delete() }
        }
    }

    private fun mergeWithFfmpeg(videoFile: String, audioFile: String, outputFile: String) {
        val process = ProcessBuilder(
            "ffmpeg", "-i", videoFile, "-i", audioFile,
            "-c", "copy", "-y", outputFile
        )
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().readText()
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("ffmpeg merge timed out")
        }
        if (process.exitValue() != 0) {
            error("ffmpeg merge failed with exit code ${process.exitValue()}")
        }
    }
}
