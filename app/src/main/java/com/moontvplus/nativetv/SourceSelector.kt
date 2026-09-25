package com.moontvplus.nativetv

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class SourceSelector(private val api: MoonApi) {
    suspend fun fastest(candidates: List<VideoItem>, episode: Int): VideoItem? {
        if (candidates.isEmpty()) return null
        val sameTitle = candidates.filter { sameWork(candidates.first(), it) }
        val eligible = mutableListOf<VideoItem>()
        val results = mutableListOf<Pair<VideoItem, Long>>()
        withTimeoutOrNull(5000) {
            coroutineScope {
                sameTitle.take(8).map { candidate ->
                    async {
                        try {
                            val detail = if (candidate.episodes.isEmpty()) api.detail(candidate.source, candidate.id) else candidate
                            if (!sameWork(candidates.first(), detail)) return@async
                            val raw = detail.episodes.getOrNull(episode) ?: return@async
                            synchronized(eligible) { eligible += detail }
                            val url = api.resolveEpisode(raw, detail.source, detail.proxyMode)
                            val speed = api.probeMedia(url)
                            synchronized(results) { results += detail to speed }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                }.awaitAll()
            }
        }
        return results.maxByOrNull { it.second }?.first
            ?: eligible.firstOrNull()
            ?: sameTitle.firstOrNull { it.episodes.size > episode }
    }
}
