package com.abdownloadmanager.desktop.pages.youtube

object YouTubeUrlDetector {
    private val YOUTUBE_PATTERNS = listOf(
        Regex("""(?:https?://)?(?:www\.)?youtube\.com/watch\?.*v=[\w-]+"""),
        Regex("""(?:https?://)?(?:www\.)?youtube\.com/shorts/[\w-]+"""),
        Regex("""(?:https?://)?(?:www\.)?youtube\.com/live/[\w-]+"""),
        Regex("""(?:https?://)?youtu\.be/[\w-]+"""),
        Regex("""(?:https?://)?(?:www\.)?youtube\.com/embed/[\w-]+"""),
        Regex("""(?:https?://)?music\.youtube\.com/watch\?.*v=[\w-]+"""),
    )

    fun isYouTubeUrl(url: String): Boolean {
        return YOUTUBE_PATTERNS.any { it.containsMatchIn(url) }
    }
}
