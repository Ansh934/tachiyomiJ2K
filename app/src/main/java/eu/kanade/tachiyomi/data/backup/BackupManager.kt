package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CUSTOM_INFO
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CUSTOM_INFO_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_READ_MANGA
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_READ_MANGA_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK_MASK
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSerializer
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import timber.log.Timber
import java.io.FileOutputStream
import kotlin.math.max

class BackupManager(context: Context) : AbstractBackupManager(context) {

    val parser = ProtoBuf

    /**
     * Create backup Json file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    override fun createBackup(uri: Uri, flags: Int, isAutoBackup: Boolean): String {
        // Create root object
        var backup: Backup? = null

        db.inTransaction {
            val databaseManga = getFavoriteManga() + if (flags and BACKUP_READ_MANGA_MASK == BACKUP_READ_MANGA) {
                getReadManga()
            } else {
                emptyList()
            }

            backup = Backup(
                backupMangas(databaseManga, flags),
                backupCategories(),
                emptyList(),
                backupExtensionInfo(databaseManga),
            )
        }

        var file: UniFile? = null
        try {
            file = (
                if (isAutoBackup) {
                    // Get dir of file and create
                    var dir = UniFile.fromUri(context, uri)
                    dir = dir.createDirectory("automatic")

                    // Delete older backups
                    val numberOfBackups = numberOfBackups()
                    val backupRegex = Regex("""tachiyomi_\d+-\d+-\d+_\d+-\d+.proto.gz""")
                    dir.listFiles { _, filename -> backupRegex.matches(filename) }
                        .orEmpty()
                        .sortedByDescending { it.name }
                        .drop(numberOfBackups - 1)
                        .forEach { it.delete() }

                    // Create new file to place backup
                    dir.createFile(Backup.getBackupFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }
                )
                ?: throw Exception("Couldn't create backup file")

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on file")
            }

            val byteArray = parser.encodeToByteArray(BackupSerializer, backup!!)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.empty_backup_error))
            }

            file.openOutputStream().also {
                // Force overwrite old file
                (it as? FileOutputStream)?.channel?.truncate(0)
            }.sink().gzip().buffer().use { it.write(byteArray) }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator().validate(context, fileUri)

            return fileUri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            file?.delete()
            throw e
        }
    }

    private fun backupMangas(mangas: List<Manga>, flags: Int): List<BackupManga> {
        return mangas.map {
            backupManga(it, flags)
        }
    }

    private fun backupExtensionInfo(mangas: List<Manga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map { it.source }
            .distinct()
            .map { sourceManager.getOrStub(it) }
            .map { BackupSource.copyFrom(it) }
            .toList()
    }

    /**
     * Backup the categories of library
     *
     * @return list of [BackupCategory] to be backed up
     */
    private fun backupCategories(): List<BackupCategory> {
        return db.getCategories()
            .executeAsBlocking()
            .map { BackupCategory.copyFrom(it) }
    }

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @param options options for the backup
     * @return [BackupManga] containing manga in a serializable form
     */
    private fun backupManga(manga: Manga, options: Int): BackupManga {
        // Entry for this manga
        val mangaObject = BackupManga.copyFrom(manga, if (options and BACKUP_CUSTOM_INFO_MASK == BACKUP_CUSTOM_INFO) customMangaManager else null)

        // Check if user wants chapter information in backup
        if (options and BACKUP_CHAPTER_MASK == BACKUP_CHAPTER) {
            // Backup all the chapters
            val chapters = db.getChapters(manga).executeAsBlocking()
            if (chapters.isNotEmpty()) {
                mangaObject.chapters = chapters.map { BackupChapter.copyFrom(it) }
            }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            // Backup categories for this manga
            val categoriesForManga = db.getCategoriesForManga(manga).executeAsBlocking()
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.mapNotNull { it.order }
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
            val tracks = db.getTracks(manga).executeAsBlocking()
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks.map { BackupTracking.copyFrom(it) }
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
            val historyForManga = db.getHistoryByMangaId(manga.id!!).executeAsBlocking()
            if (historyForManga.isNotEmpty()) {
                val history = historyForManga.mapNotNull { history ->
                    val url = db.getChapter(history.chapter_id).executeAsBlocking()?.url
                    url?.let { BackupHistory(url, history.last_read, history.time_read) }
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }

    fun restoreExistingManga(manga: Manga, dbManga: Manga) {
        manga.id = dbManga.id
        manga.copyFrom(dbManga)
        insertManga(manga)
    }

    /**
     * Fetches manga information
     *
     * @param manga manga that needs updating
     * @return Updated manga info.
     */
    fun restoreNewManga(manga: Manga): Manga {
        return manga.also {
            it.initialized = it.description != null
            it.id = insertManga(it)
        }
    }

    /**
     * Restore the categories from Json
     *
     * @param backupCategories list containing categories
     */
    internal fun restoreCategories(backupCategories: List<BackupCategory>) {
        // Get categories from file and from db
        val dbCategories = db.getCategories().executeAsBlocking()

        // Iterate over them
        backupCategories.map { it.getCategoryImpl() }.forEach { category ->
            // Used to know if the category is already in the db
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category.id = dbCategory.id
                    found = true
                    break
                }
            }
            // If the category isn't in the db, remove the id and insert a new category
            // Store the inserted id in the category
            if (!found) {
                // Let the db assign the id
                category.id = null
                val result = db.insertCategory(category).executeAsBlocking()
                category.id = result.insertedId()?.toInt()
            }
        }
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal fun restoreCategories(manga: Manga, categories: List<Int>, backupCategories: List<BackupCategory>) {
        val dbCategories = db.getCategories().executeAsBlocking()
        val mangaCategoriesToUpdate = ArrayList<MangaCategory>(categories.size)
        categories.forEach { backupCategoryOrder ->
            backupCategories.firstOrNull {
                it.order == backupCategoryOrder
            }?.let { backupCategory ->
                dbCategories.firstOrNull { dbCategory ->
                    dbCategory.name == backupCategory.name
                }?.let { dbCategory ->
                    mangaCategoriesToUpdate += MangaCategory.create(manga, dbCategory)
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            db.deleteOldMangasCategories(listOf(manga)).executeAsBlocking()
            db.insertMangasCategories(mangaCategoriesToUpdate).executeAsBlocking()
        }
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal fun restoreHistoryForManga(history: List<BackupHistory>) {
        // List containing history to be updated
        val historyToBeUpdated = ArrayList<History>(history.size)
        for ((url, lastRead, readDuration) in history) {
            val dbHistory = db.getHistoryByChapterUrl(url).executeAsBlocking()
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory.apply {
                    last_read = max(lastRead, dbHistory.last_read)
                    time_read = max(readDuration, dbHistory.time_read)
                }
                historyToBeUpdated.add(dbHistory)
            } else {
                // If not in database create
                db.getChapter(url).executeAsBlocking()?.let {
                    val historyToAdd = History.create(it).apply {
                        last_read = lastRead
                        time_read = readDuration
                    }
                    historyToBeUpdated.add(historyToAdd)
                }
            }
        }
        db.upsertHistoryLastRead(historyToBeUpdated).executeAsBlocking()
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    internal fun restoreTrackForManga(manga: Manga, tracks: List<Track>) {
        // Fix foreign keys with the current manga id
        tracks.map { it.manga_id = manga.id!! }

        // Get tracks from database
        val dbTracks = db.getTracks(manga).executeAsBlocking()
        val trackToUpdate = mutableListOf<Track>()

        tracks.forEach { track ->
            var isInDatabase = false
            for (dbTrack in dbTracks) {
                if (track.sync_id == dbTrack.sync_id) {
                    // The sync is already in the db, only update its fields
                    if (track.media_id != dbTrack.media_id) {
                        dbTrack.media_id = track.media_id
                    }
                    if (track.library_id != dbTrack.library_id) {
                        dbTrack.library_id = track.library_id
                    }
                    dbTrack.last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read)
                    isInDatabase = true
                    trackToUpdate.add(dbTrack)
                    break
                }
            }
            if (!isInDatabase) {
                // Insert new sync. Let the db assign the id
                track.id = null
                trackToUpdate.add(track)
            }
        }
        // Update database
        if (trackToUpdate.isNotEmpty()) {
            db.insertTracks(trackToUpdate).executeAsBlocking()
        }
    }

    internal fun restoreChapters(manga: Manga, chapters: List<Chapter>) {
        val dbChapters = db.getChapters(manga).executeAsBlocking()

        chapters.forEach { chapter ->
            val dbChapter = dbChapters.find { it.url == chapter.url }
            if (dbChapter != null) {
                chapter.id = dbChapter.id
                chapter.copyFrom(dbChapter as SChapter)
                if (dbChapter.read && !chapter.read) {
                    chapter.read = dbChapter.read
                    chapter.last_page_read = dbChapter.last_page_read
                } else if (chapter.last_page_read == 0 && dbChapter.last_page_read != 0) {
                    chapter.last_page_read = dbChapter.last_page_read
                }
                if (!chapter.bookmark && dbChapter.bookmark) {
                    chapter.bookmark = dbChapter.bookmark
                }
            }

            chapter.manga_id = manga.id
        }

        val newChapters = chapters.groupBy { it.id != null }
        newChapters[true]?.let { updateKnownChapters(it) }
        newChapters[false]?.let { insertChapters(it) }
    }
}