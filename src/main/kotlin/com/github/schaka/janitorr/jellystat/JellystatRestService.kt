package com.github.schaka.janitorr.jellystat

import com.github.schaka.janitorr.config.ApplicationProperties
import com.github.schaka.janitorr.jellystat.requests.ItemRequest
import com.github.schaka.janitorr.jellystat.requests.WatchHistoryResponse
import com.github.schaka.janitorr.mediaserver.MediaServerService
import com.github.schaka.janitorr.mediaserver.jellyfin.JellyfinRestService
import com.github.schaka.janitorr.mediaserver.library.LibraryType
import com.github.schaka.janitorr.servarr.LibraryItem
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Does nothing. Used in case the user does not supply Jellyfin configuration.
 */
class JellystatRestService(
        val jellystatClient: JellystatClient,
        val jellystatProperties: JellystatProperties,
        val mediaServerService: MediaServerService,
        val applicationProperties: ApplicationProperties
) : JellystatService {

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
    }

    override fun populateWatchHistory(items: List<LibraryItem>, type: LibraryType) {
        // populates per season, per show or per movie, depending on properties
        // e.g. each season item can be populated with its TV show mediaserver id
        // watch age is determined by the matched mediaserver id
        // TODO: find a better way - passing properties to an unrelated component couples them unnecessarily
        mediaServerService.populateMediaServerIds(items, type, jellystatProperties)

        for (item in items.filter { it.mediaServerId != null }) {
            // every movie, show, season and episode has its own unique ID, so every request will only consider what's passed to it here
            val watchHistory = jellystatClient.getRequests(ItemRequest(item.mediaServerId!!))
                    .filter { it.PlaybackDuration > 60 }
                    .maxByOrNull { toDate(it.ActivityDateInserted) }

            // only count view if at least one minute of content was watched - could be user adjustable later
            if (watchHistory != null) {
                item.lastSeen = toDate(watchHistory.ActivityDateInserted)
                logWatchInfo(item, watchHistory)
            }

        }
    }

    private fun logWatchInfo(item: LibraryItem, watchHistory: WatchHistoryResponse?) {
        if (watchHistory?.SeasonId != null) {
            val season = "${watchHistory.NowPlayingItemName} ${item.season}"
            log.debug("Updating history - user {} watched {} at {}", watchHistory.UserName, season, watchHistory.ActivityDateInserted)
        } else {
            log.debug("Updating history - user {} watched {} at {}", watchHistory?.UserName, watchHistory?.NowPlayingItemName, watchHistory?.ActivityDateInserted)
        }
    }

    private fun toDate(date: String): LocalDateTime {
        return LocalDateTime.parse(date.substring(0, date.length - 1))
    }

}