package com.github.schaka.janitorr.stats.streamystats

import com.github.schaka.janitorr.config.ApplicationProperties
import com.github.schaka.janitorr.mediaserver.AbstractMediaServerService
import com.github.schaka.janitorr.mediaserver.library.LibraryType
import com.github.schaka.janitorr.mediaserver.lookup.MediaLookup
import com.github.schaka.janitorr.mediaserver.lookup.ResolvedMediaServerIds
import com.github.schaka.janitorr.servarr.LibraryItem
import com.github.schaka.janitorr.stats.StatsService
import com.github.schaka.janitorr.stats.streamystats.requests.StreamystatsHistoryResponse
import com.github.schaka.janitorr.stats.streamystats.requests.WatchHistoryEntry
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME

class StreamystatsRestService(
    val streamystatsClient: StreamystatsClient,
    val streamystatsProperties: StreamystatsProperties,
    val mediaServerService: AbstractMediaServerService,
    val applicationProperties: ApplicationProperties
) : StatsService {

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun populateWatchHistory(items: List<LibraryItem>, type: LibraryType) {
        val bySeason = if (applicationProperties.wholeTvShow) false else !streamystatsProperties.wholeTvShow
        val libraryMappings = mediaServerService.getMediaServerIdsForLibraryWithFallback(items, type, bySeason)

        for (item in items) {
            val lookupKey = if (type == LibraryType.TV_SHOWS && bySeason) MediaLookup(item.id, item.season) else MediaLookup(item.id)
            val resolved = libraryMappings.getOrDefault(lookupKey, ResolvedMediaServerIds(emptyList()))

            var (watchHistory, response) = queryStreamystats(resolved.ids)

            if (bySeason && watchHistory == null && resolved.fallbackIds.isNotEmpty()) {
                log.debug("No watch history via season IDs for {} (season {}), falling back to show-level IDs", item.id, item.season)
                val (fallbackWatch, fallbackResponse) = if (streamystatsProperties.seasonFallback) {
                    queryStreamystatsWithSeasonFallback(resolved.fallbackIds, item.season!!)
                } else {
                    queryStreamystats(resolved.fallbackIds, seasonIdFilter = resolved.ids.toSet())
                }
                watchHistory = fallbackWatch
                response = fallbackResponse
            }

            if (watchHistory != null) {
                item.lastSeen = toDate(watchHistory.watchDate)
                logWatchInfo(item, watchHistory, response)
            }
        }
    }

    private fun queryStreamystats(jellyfinIds: List<String>, seasonIdFilter: Set<String>? = null): Pair<WatchHistoryEntry?, StreamystatsHistoryResponse?> {
        val responses = jellyfinIds.mapNotNull(::gracefulQuery)
            .filter { seasonIdFilter == null || (it.item.seasonId != null && it.item.seasonId in seasonIdFilter) }
        val firstResponse = responses.firstOrNull()
        val watchHistory = responses
            .filter { it.lastWatched != null }
            .flatMap { it.watchHistory }
            .filter { it.watchDuration > 60 }
            .maxByOrNull { toDate(it.watchDate) }
        return watchHistory to firstResponse
    }

    private fun queryStreamystatsWithSeasonFallback(showIds: List<String>, targetSeason: Int): Pair<WatchHistoryEntry?, StreamystatsHistoryResponse?> {
        val showResponses = showIds.mapNotNull(::gracefulQuery)

        val candidateSeasonIds = showResponses
            .flatMap { it.watchHistory }
            .mapNotNull { it.seasonId }
            .distinct()

        val matchingSeasonResponse = candidateSeasonIds
            .mapNotNull(::gracefulQuery)
            .firstOrNull { it.item.indexNumber == targetSeason }
            ?: return null to null

        val matchingSeasonId = matchingSeasonResponse.item.id

        val watchEntry = showResponses
            .filter { it.lastWatched != null }
            .flatMap { it.watchHistory }
            .filter { it.seasonId == matchingSeasonId && it.watchDuration > 60 }
            .maxByOrNull { toDate(it.watchDate) }

        return watchEntry to matchingSeasonResponse
    }

    private fun gracefulQuery(jellyfinId: String): StreamystatsHistoryResponse? {
        try {
            return streamystatsClient.getRequests(jellyfinId)
        } catch (e: Exception) {
            if (log.isTraceEnabled) {
                log.warn("Stats via Streamystats not found for Jellyfin ID: {}", jellyfinId)
            } else {
                log.warn("Stats via Streamystats not found for Jellyfin ID: {}", jellyfinId, e)
            }
        }
        return null
    }

    private fun logWatchInfo(item: LibraryItem, watchHistory: WatchHistoryEntry?, response: StreamystatsHistoryResponse?) {
        if (response?.item?.type == "Season") {
            val season = "${response.item.seriesName} ${response.item.name}"
            log.debug("Updating history - user {} watched {} at {}", watchHistory?.user?.name, season, watchHistory?.watchDate)
        } else {
            log.debug("Updating history - user {} watched {} at {}", watchHistory?.user?.name, response?.item?.name, watchHistory?.watchDate)
        }
    }

    private fun toDate(date: String): LocalDateTime {
        // 2025-04-16T05:27:15Z
        return LocalDateTime.parse(date.dropLast(1), ISO_LOCAL_DATE_TIME)
    }

}