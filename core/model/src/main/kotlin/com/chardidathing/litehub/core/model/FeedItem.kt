package com.chardidathing.litehub.core.model

// one headline. published is null when the feed didn't say or said it in a way we can't read
data class FeedItem(
    val source: String,
    val id: String,
    val title: String,
    val link: String?,
    val publishedMs: Long?,
)

// every item from every feed source, newest first, with how each source's last refresh went
data class FeedSnapshot(val items: List<FeedItem>, val status: Map<String, SourceStatus>)
