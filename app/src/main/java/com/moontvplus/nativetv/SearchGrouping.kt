package com.moontvplus.nativetv

internal data class SearchGroup(val year: String, val items: List<VideoItem>) {
    val representative: VideoItem
        get() = items.first().copy(
            poster = items.firstOrNull { it.poster.isNotBlank() }?.poster.orEmpty(),
            year = year.ifBlank { items.first().year }
        )

    val sourceNames: List<String>
        get() = items.distinctBy { it.source }.map { it.sourceName.ifBlank { it.source } }

    // The web search card uses the most frequent nonzero episode count in the group.
    val episodeCount: Int
        get() = items.map { it.episodes.size }.filter { it > 0 }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
}

// Follow the web search page: group by normalized title and type, then split by known year.
// Results without a year are available in every known-year group for the same title and type.
internal fun groupSearchResults(results: List<VideoItem>): List<SearchGroup> {
    val byTitleAndType = linkedMapOf<Pair<String, String>, MutableList<VideoItem>>()
    results.forEach { item ->
        val title = normalizeTitle(item.title)
        if (title.isNotBlank()) byTitleAndType.getOrPut(title to searchMediaType(item)) { mutableListOf() }.add(item)
    }

    return buildList {
        byTitleAndType.values.forEach { sameTitle ->
            val withYear = linkedMapOf<String, MutableList<VideoItem>>()
            val withoutYear = mutableListOf<VideoItem>()
            sameTitle.forEach { item ->
                val year = item.year.trim()
                if (year.matches(Regex("\\d{4}"))) withYear.getOrPut(year) { mutableListOf() }.add(item)
                else withoutYear.add(item)
            }
            if (withYear.isEmpty()) add(SearchGroup("", withoutYear))
            else withYear.forEach { (year, items) -> add(SearchGroup(year, items + withoutYear)) }
        }
    }
}

private fun searchMediaType(item: VideoItem): String {
    val category = item.category.lowercase()
    if (category.contains("电影") || category.contains("movie") ||
        (category.endsWith("片") && !category.contains("动漫"))) return "movie"
    if (listOf("剧", "动漫", "综艺", "anime").any { category.contains(it) }) return "tv"
    if (item.episodeTitles.firstOrNull()?.contains(Regex("第\\d+[集话]|EP?\\d+", RegexOption.IGNORE_CASE)) == true) return "tv"
    return if (item.episodes.size == 1) "movie" else "tv"
}
