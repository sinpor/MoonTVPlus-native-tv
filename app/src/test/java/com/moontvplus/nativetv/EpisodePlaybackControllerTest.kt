package com.moontvplus.nativetv

import org.junit.Assert.*
import org.junit.Test

class EpisodePlaybackControllerTest {
    private val controller = EpisodePlaybackController()

    @Test fun endAdvancesOnlyOnceWithinTheCurrentList() {
        val first = controller.beginRequest()
        assertEquals(1, controller.nextEpisodeOnEnd(first, 0, 3))
        assertNull(controller.nextEpisodeOnEnd(first, 0, 3))
        val second = controller.beginRequest()
        assertNull(controller.nextEpisodeOnEnd(first, 1, 2))
    }

    @Test fun manualChoiceInvalidatesEndedMediaAndPendingAutomaticLoad() {
        val first = controller.beginRequest()
        assertEquals(1, controller.nextEpisodeOnEnd(first, 0, 8))
        val automatic = controller.beginRequest()
        val manual = controller.beginRequest()
        assertFalse(controller.isCurrent(automatic))
        assertNull(controller.nextEpisodeOnEnd(first, 0, 8))
        assertFalse(controller.onPlaying(automatic))
        assertTrue(controller.isCurrent(manual))
        assertTrue(controller.onPlaying(manual))
    }

    @Test fun newChoiceSupersedesPendingSourceDetails() {
        val sourceRequest = controller.beginRequest()
        val episodeRequest = controller.beginRequest()
        assertFalse(controller.isCurrent(sourceRequest))
        assertTrue(controller.isCurrent(episodeRequest))
        assertNull(controller.nextEpisodeOnEnd(sourceRequest, 3, 12))
    }

    @Test fun backgroundEndCanPrepareNextButCannotPlayOrSaveIt() {
        val current = controller.beginRequest()
        assertTrue(controller.onPlaying(current))
        controller.onBackground()
        assertEquals(1, controller.nextEpisodeOnEnd(current, 0, 3))
        assertTrue(controller.canSaveProgress(current))
        val next = controller.beginRequest()
        assertFalse(controller.mayPlay)
        assertFalse(controller.onPlaying(next))
        assertFalse(controller.canSaveProgress(next))
        controller.onForeground()
        assertFalse(controller.mayPlay)
        assertFalse(controller.onPlaying(next))
        assertTrue(controller.allowManualPlayback())
        assertTrue(controller.mayPlay)
        assertTrue(controller.onPlaying(next))
        assertTrue(controller.canSaveProgress(next))
    }

    @Test fun backgroundManualActionCannotClearPause() {
        controller.onBackground()
        assertFalse(controller.allowManualPlayback())
        controller.beginRequest()
        assertFalse(controller.mayPlay)
        controller.onForeground()
        assertFalse(controller.mayPlay)
    }

    @Test fun firstPlaybackWritesStartOnceAndResumingKeepsProgressEligible() {
        val request = controller.beginRequest()
        assertFalse(controller.canSaveProgress(request))
        assertTrue(controller.onPlaying(request))
        assertTrue(controller.canSaveProgress(request))
        assertFalse(controller.onPlaying(request))
        controller.onBackground()
        assertTrue(controller.canSaveProgress(request))
        controller.onForeground()
        controller.allowManualPlayback()
        assertFalse(controller.onPlaying(request))
        assertTrue(controller.canSaveProgress(request))
    }

    @Test fun failedOrUnplayedNextCannotOverwriteFinishedEpisodeProgress() {
        val previous = controller.beginRequest()
        controller.onPlaying(previous)
        assertTrue(controller.canSaveProgress(previous))
        val next = controller.beginRequest()
        assertFalse(controller.canSaveProgress(previous))
        assertFalse(controller.canSaveProgress(next))
        assertFalse(controller.onPlaying(previous))
        assertFalse(controller.canSaveProgress(next))
    }

    @Test fun endOfResumedMediaCanAdvanceWithoutANewPlayingCallback() {
        val request = controller.beginRequest()
        // Resuming an already-ended position may produce ENDED without isPlaying=true.
        assertEquals(4, controller.nextEpisodeOnEnd(request, 3, 5))
        assertNull(controller.nextEpisodeOnEnd(request, 3, 5))
    }

    @Test fun absentMediaAndInvalidIndicesDoNotAdvance() {
        val request = controller.beginRequest()
        assertFalse(controller.isCurrent(null))
        assertNull(controller.nextEpisodeOnEnd(null, 0, 5))
        assertNull(controller.nextEpisodeOnEnd(request, -1, 5))
        assertNull(controller.nextEpisodeOnEnd(request, 0, 0))
        assertNull(controller.nextEpisodeOnEnd(request, 5, 5))
        assertEquals(1, controller.nextEpisodeOnEnd(request, 0, 5))
    }
}
