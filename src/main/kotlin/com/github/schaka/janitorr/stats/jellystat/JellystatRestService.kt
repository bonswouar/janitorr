package com.github.schaka.janitorr.stats.jellystat

import com.github.schaka.janitorr.config.ApplicationProperties
import com.github.schaka.janitorr.mediaserver.AbstractMediaServerService
import com.github.schaka.janitorr.mediaserver.library.LibraryType
import com.github.schaka.janitorr.mediaserver.lookup.MediaLookup
import com.github.schaka.janitorr.mediaserver.lookup.ResolvedMediaServerIds
import com.github.schaka.janitorr.servarr.LibraryItem
import com.github.schaka.janitorr.stats.StatsService
import com.github.schaka.janitorr.stats.jellystat.requests.JellyStatHistoryResponse
import com.github.schaka.janitorr.stats.jellystat.requests.JellystatItemRequest
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class JellystatRestService(
    val jellystatClient: JellystatClient,
    val jellystatProperties: JellystatProperties,
    val mediaServerService: AbstractMediaServerService,
    val applicationProperties: ApplicationProperties
) : StatsService {

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun populateWatchHistory(items: List<LibraryItem>, type: LibraryType) {
        val bySeason = if (applicationProperties.wholeTvShow) false else !jellystatProperties.wholeTvShow
        val libraryMappings = mediaServerService.getMediaServerIdsForLibraryWithFallback(items, type, bySeason)

        for (item in items) {
            val lookupKey = if (type == LibraryType.TV_SHOWS && bySeason) MediaLookup(item.id, item.season) else MediaLookup(item.id)
            val resolved = libraryMappings.getOrDefault(lookupKey, ResolvedMediaServerIds(emptyList()))

            var watchHistory = queryJellystat(resolved.ids)

            if (watchHistory == null && resolved.fallbackIds.isNotEmpty()) {
                log.debug("No watch history via season IDs for {} (season {}), falling back to show-level IDs", item.id, item.season)
                watchHistory = if (jellystatProperties.seasonFallback) {
                    queryJellystat(resolved.fallbackIds, seasonNumber = item.season)
                } else {
                    queryJellystat(resolved.fallbackIds, seasonIdFilter = resolved.ids.toSet())
                }
            }

            if (watchHistory != null) {
                item.lastSeen = toDate(watchHistory.ActivityDateInserted)
                logWatchInfo(item, watchHistory)
            }
        }
    }

    private fun queryJellystat(jellyfinIds: List<String>, seasonIdFilter: Set<String>? = null, seasonNumber: Int? = null): JellyStatHistoryResponse? {
        return jellyfinIds
            .map(::JellystatItemRequest)
            .map(jellystatClient::getRequests)
            .flatMap { page -> page.results }
            .filter { it.PlaybackDuration > 60 }
            .filter { seasonIdFilter == null || (it.SeasonId != null && it.SeasonId in seasonIdFilter) }
            .filter { seasonNumber == null || it.SeasonNumber == seasonNumber }
            .maxByOrNull { toDate(it.ActivityDateInserted) }
    }

    private fun logWatchInfo(item: LibraryItem, watchHistory: JellyStatHistoryResponse?) {
        if (watchHistory?.SeasonId != null) {
            val season = "${watchHistory.NowPlayingItemName} Season ${item.season}"
            log.debug("Updating history - user {} watched {} at {}", watchHistory.UserName, season, watchHistory.ActivityDateInserted)
        } else {
            log.debug("Updating history - user {} watched {} at {}", watchHistory?.UserName, watchHistory?.NowPlayingItemName, watchHistory?.ActivityDateInserted)
        }
    }

    private fun toDate(date: String): LocalDateTime {
        return LocalDateTime.parse(date.dropLast(1))
    }

}