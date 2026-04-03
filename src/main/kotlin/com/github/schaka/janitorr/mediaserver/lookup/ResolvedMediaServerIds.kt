package com.github.schaka.janitorr.mediaserver.lookup

data class ResolvedMediaServerIds(
    val ids: List<String>,
    val fallbackIds: List<String> = emptyList()
)
