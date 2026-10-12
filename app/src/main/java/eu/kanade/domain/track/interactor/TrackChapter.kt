package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateWorker
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack

@Inject
class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val upsertTrack: UpsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, chapterUrl: String? = null, setupJobOnFailure: Boolean = true, trackerId: Long? = null, saveToDb: Boolean = true) {
        coroutineScope {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@coroutineScope

            tracks.filter { trackerId == null || it.trackerId == trackerId }.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn) {
                    return@mapNotNull null
                }
                val isKomga = service is eu.kanade.tachiyomi.data.track.komga.Komga
                if (!isKomga && chapterNumber <= track.lastChapterRead) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val isKomga = service is eu.kanade.tachiyomi.data.track.komga.Komga
                            val refreshedTrack = if (isKomga && chapterUrl != null) {
                                track
                            } else {
                                service.refresh(track.toDbTrack()).toDomainTrack(idRequired = true)!!
                            }

                            val updatedTrack = if (isKomga) {
                                refreshedTrack.copy(lastChapterRead = 0.0)
                            } else {
                                refreshedTrack.copy(lastChapterRead = maxOf(refreshedTrack.lastChapterRead, chapterNumber))
                            }

                            service.update(updatedTrack.toDbTrack(), true, chapterUrl)
                            if (saveToDb) {
                                upsertTrack.await(updatedTrack)
                            }
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            if (!isKomga) {
                                delayedTrackingStore.add(track.id, chapterNumber)
                                if (setupJobOnFailure) {
                                    DelayedTrackingUpdateWorker.setupTask(context)
                                }
                            }
                            logcat(LogPriority.WARN, e) { "Failed to update tracker ${service.name}" }
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
