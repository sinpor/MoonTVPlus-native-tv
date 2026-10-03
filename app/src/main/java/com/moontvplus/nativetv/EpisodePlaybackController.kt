package com.moontvplus.nativetv

/** Tracks which media may advance or write progress, including across asynchronous loads. */
internal class EpisodePlaybackController {
    private var sequence = 0L
    private var requestId: String? = null
    private var started = false
    private var endHandled = false
    private var background = false
    var pausedByBackground = false
        private set

    val mayPlay get() = !background && !pausedByBackground

    fun beginRequest(): String {
        val id = (++sequence).toString()
        requestId = id
        started = false
        endHandled = false
        return id
    }

    fun isCurrent(mediaId: String?): Boolean = mediaId != null && mediaId == requestId

    /** Returns true only on the first actual playback of the current request. */
    fun onPlaying(mediaId: String?): Boolean {
        if (!isCurrent(mediaId) || !mayPlay || started) return false
        started = true
        return true
    }

    fun canSaveProgress(mediaId: String?): Boolean = isCurrent(mediaId) && started

    fun nextEpisodeOnEnd(mediaId: String?, episode: Int, episodeCount: Int): Int? {
        if (!isCurrent(mediaId) || endHandled || episode !in 0 until episodeCount) return null
        endHandled = true
        return (episode + 1).takeIf { it < episodeCount }
    }

    fun onBackground() {
        background = true
        pausedByBackground = true
    }

    fun onForeground() { background = false }

    /** Only an explicit foreground action can clear the background pause. */
    fun allowManualPlayback(): Boolean {
        if (background) return false
        pausedByBackground = false
        return true
    }
}
