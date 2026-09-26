package com.moontvplus.nativetv

import org.junit.Assert.*
import org.junit.Test

class FullscreenSeekControllerTest {
    private class Playback : SeekPlayback {
        override var position = 120_000L
        override var duration = 2_700_000L
        override var canSeek = true
        override var playing = true
        val seeks = mutableListOf<Long>()
        override fun seekTo(position: Long) {
            seeks += position
            // Media3 exposes the requested position immediately, even while buffering.
            this.position = position
        }
        override fun setPlayWhenReady(playing: Boolean) { this.playing = playing }
    }

    private val playback = Playback()
    private var visible: SeekSelection? = null
    private val controller = FullscreenSeekController(playback) { visible = it }

    @Test fun shortPressPreviewsImmediatelyAndSeeksOnlyOnRelease() {
        controller.press(1, 0)
        assertEquals(130_000L, visible!!.target)
        controller.advance(499)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.playing)
        controller.release(1)
        assertEquals(listOf(130_000L), playback.seeks)
        assertNull(visible)
    }

    @Test fun longPressFreezesOriginAndCommitsOnceAfterAccelerating() {
        controller.press(1, 0)
        playback.position = 120_400L // Playback continues during short-press recognition.
        controller.advance(500)
        assertFalse(playback.playing)
        for (time in 750L..5_000L step 250L) controller.advance(time)
        assertEquals(120_000L, visible!!.origin)
        assertEquals(460_000L, visible!!.target)
        assertTrue(playback.seeks.isEmpty())
        controller.release(1)
        assertEquals(listOf(460_000L), playback.seeks)
        assertTrue(playback.playing)
    }

    @Test fun releaseAtThresholdPromotesEvenIfTimerHasNotRunYet() {
        controller.press(1, 0)
        controller.advance(501)
        controller.release(1)
        assertEquals(listOf(140_000L), playback.seeks)
        assertTrue(playback.playing)
    }

    @Test fun pausedPlaybackStaysPausedAfterCommit() {
        playback.playing = false
        controller.press(-1, 0)
        controller.advance(500)
        controller.release(-1)
        assertFalse(playback.playing)
        assertEquals(listOf(100_000L), playback.seeks)
    }

    @Test fun cancelRestoresPlaybackAndReleaseCannotCommit() {
        controller.press(1, 0)
        controller.advance(500)
        controller.cancel()
        assertTrue(playback.playing)
        controller.press(1, 750, repeat = true)
        controller.advance(1_000)
        controller.release(1)
        assertNull(visible)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun backgroundCancellationCannotResumePlayback() {
        controller.press(1, 0)
        controller.advance(500)
        controller.cancel(restorePlayback = false)
        controller.release(1)
        assertFalse(playback.playing)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun nativeRepeatsDoNotMoveTargetOrRestartAcceleration() {
        controller.press(1, 0)
        controller.press(1, 300, repeat = true)
        controller.press(1, 400)
        assertEquals(130_000L, visible!!.target)
        assertEquals(0L, visible!!.pressedAt)
        controller.advance(500)
        assertEquals(140_000L, visible!!.target)
    }

    @Test fun reachingEndWaitsForReleaseAndSeeksOnce() {
        playback.position = playback.duration - 15_000L
        controller.press(1, 0)
        controller.advance(500)
        controller.advance(750)
        assertEquals(playback.duration - 1_000L, visible!!.target)
        assertTrue(playback.seeks.isEmpty())
        assertFalse(playback.playing)
        controller.release(1)
        assertEquals(listOf(playback.duration - 1_000L), playback.seeks)
    }

    @Test fun beginningAndVeryShortMediaHaveValidBounds() {
        playback.position = 3_000L
        controller.press(-1, 0)
        controller.advance(500)
        assertEquals(0L, visible!!.target)
        controller.release(-1)
        playback.duration = 500L
        controller.press(1, 1_000)
        controller.advance(1_500)
        assertEquals(0L, visible!!.target)
        controller.release(1)
        assertEquals(listOf(0L, 0L), playback.seeks)
    }

    @Test fun reverseCorrectionStartsAtCommittedPositionAndResetsSpeed() {
        controller.press(1, 0)
        controller.advance(5_000)
        controller.release(1)
        controller.press(-1, 5_100)
        assertEquals(160_000L, visible!!.origin)
        assertEquals(150_000L, visible!!.target)
        controller.advance(5_600)
        assertEquals(140_000L, visible!!.target)
        controller.release(-1)
        assertEquals(listOf(160_000L, 140_000L), playback.seeks)
    }

    @Test fun simultaneousOppositeKeysCancelAndNeitherReleaseCommits() {
        controller.press(1, 0)
        controller.advance(500)
        controller.press(-1, 600)
        assertNull(visible)
        assertTrue(playback.playing)
        controller.release(1)
        controller.release(-1)
        assertTrue(playback.seeks.isEmpty())
        controller.press(-1, 1_000)
        assertNotNull(visible)
    }

    @Test fun unavailableMediaDoesNotSeekAndRequiresAnotherPress() {
        playback.canSeek = false
        controller.press(1, 0)
        playback.canSeek = true
        controller.press(1, 500, repeat = true)
        controller.release(1)
        assertNull(visible)
        assertTrue(playback.seeks.isEmpty())
        controller.press(1, 1_000)
        controller.advance(1_500)
        playback.duration = -1
        controller.release(1)
        assertTrue(playback.seeks.isEmpty())
        assertTrue(playback.playing)
    }

    @Test fun lostSeekabilityCancelsDuringHold() {
        controller.press(1, 0)
        controller.advance(500)
        playback.canSeek = false
        controller.advance(750)
        assertNull(visible)
        assertTrue(playback.playing)
        playback.canSeek = true
        controller.press(1, 1_000, repeat = true)
        controller.release(1)
        assertTrue(playback.seeks.isEmpty())
    }

    @Test fun regainingFocusHandlesMissingReleaseWithoutAcceptingOldRepeats() {
        controller.press(1, 0)
        controller.advance(500)
        controller.cancel()
        controller.resetInput()
        controller.press(1, 1_000, repeat = true)
        controller.release(1)
        assertNull(visible)
        controller.press(1, 1_500)
        controller.release(1)
        assertEquals(listOf(130_000L), playback.seeks)
    }
}
