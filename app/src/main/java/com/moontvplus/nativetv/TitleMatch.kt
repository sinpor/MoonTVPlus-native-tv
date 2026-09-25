package com.moontvplus.nativetv

import java.util.Locale

internal fun sameWork(first: VideoItem, second: VideoItem): Boolean {
    val left = normalizeTitle(first.title)
    val right = normalizeTitle(second.title)
    return left.isNotBlank() && left == right &&
        (first.year.isBlank() || second.year.isBlank() || first.year == second.year)
}

internal fun matchesSearch(query: String, title: String): Boolean {
    val needle = normalizeTitle(query)
    return needle.isNotBlank() && normalizeTitle(title).contains(needle)
}

internal fun normalizeTitle(title: String): String = title.lowercase(Locale.ROOT)
    .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
