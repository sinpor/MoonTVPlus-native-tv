package com.moontvplus.nativetv

internal interface SeekPlayback {
    val position: Long
    val duration: Long
    val canSeek: Boolean
    val playing: Boolean
    fun seekTo(position: Long)
    fun setPlayWhenReady(playing: Boolean)
}

internal data class SeekSelection(
    val direction: Int,
    val origin: Long,
    val target: Long,
    val pressedAt: Long,
    val wasPlaying: Boolean,
    val longPress: Boolean = false
)

/** Owns one uncommitted selection; the UI timer never seeks the media itself. */
internal class FullscreenSeekController(
    private val playback: SeekPlayback,
    private val onSelectionChanged: (SeekSelection?) -> Unit
) {
    var selection: SeekSelection? = null
        private set
    private val heldDirections = mutableSetOf<Int>()

    fun isHeld(direction: Int): Boolean = direction in heldDirections

    fun resetInput() {
        cancel()
        heldDirections.clear()
    }

    private fun publish(value: SeekSelection?) {
        selection = value
        onSelectionChanged(value)
    }

    fun press(direction: Int, now: Long, repeat: Boolean = false) {
        require(direction == -1 || direction == 1)
        if (repeat || !heldDirections.add(direction)) return
        // Conflicting directions cancel the selection. Their later UP events cannot commit it.
        if (heldDirections.size > 1) {
            cancel()
            return
        }
        if (!playback.canSeek || playback.duration <= 0) return
        val origin = playback.position.coerceAtLeast(0)
        publish(SeekSelection(direction, origin, clamp(origin + direction * 10_000L), now, playback.playing))
    }

    /** Called first at 500 ms, then every 250 ms by the UI's single timer. */
    fun advance(now: Long) {
        val current = selection ?: return
        if (!playback.canSeek || playback.duration <= 0) {
            cancel()
            return
        }
        val elapsed = now - current.pressedAt
        if (elapsed < 500) return
        if (!current.longPress) playback.setPlayWhenReady(false)
        val step = when {
            elapsed < 2_000 -> 10_000L
            elapsed < 5_000 -> 20_000L
            else -> 30_000L
        }
        publish(current.copy(target = clamp(current.target + current.direction * step), longPress = true))
    }

    fun release(direction: Int) {
        if (!heldDirections.remove(direction)) return
        val current = selection ?: return
        if (current.direction != direction) return
        val valid = playback.canSeek && playback.duration > 0
        val target = if (valid) clamp(current.target) else 0L
        publish(null)
        if (valid) playback.seekTo(target)
        if (current.longPress) playback.setPlayWhenReady(current.wasPlaying)
    }

    fun cancel(restorePlayback: Boolean = true) {
        val current = selection ?: return
        publish(null)
        if (restorePlayback && current.longPress) playback.setPlayWhenReady(current.wasPlaying)
        // Keep held keys until release so native repeats cannot start a new selection.
    }

    private fun clamp(target: Long): Long = target.coerceIn(0L, (playback.duration - 1_000L).coerceAtLeast(0))
}
