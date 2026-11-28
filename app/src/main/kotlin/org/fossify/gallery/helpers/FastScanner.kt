package org.fossify.gallery.helpers

import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import androidx.annotation.RequiresApi
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.getStringValue
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isExternalStorageManager
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.sdCardPath
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.isRPlus
import org.fossify.gallery.extensions.deleteDBPath
import org.fossify.gallery.extensions.mediaDB
import org.fossify.gallery.extensions.config
import org.fossify.gallery.models.Medium
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Filesystem based scanner. It replaces the MediaStore folder discovery when the app may read the whole storage
 * (pre-Android 11, or "All files access" granted):
 *  - finds hidden / .nomedia trees MediaStore never indexes, also when they are moved in with a file manager
 *  - keeps an index of all folders on disk, only folders whose timestamp changed are listed again
 *  - watches the folders with inotify and MediaStore, so changes show up right away
 *  - deletes in the background with a result per file
 * Without all-files access every caller keeps using the previous MediaStore based code.
 */
object FastScanner {
    private const val POLL_PERIOD = 4000L
    private const val DEFAULT_POLL_PERIOD = 3000L
    private const val REFRESH_MAX_AGE = 1500L
    private const val DEBOUNCE = 250L
    private const val HOT_DEPTH = 3
    private const val SCAN_PREFS = "fast_scanner_state"
    private const val KEY_MS_GENERATION = "ms_generation"
    private const val KEY_MS_SECONDS = "ms_seconds"
    private const val BACKGROUND_WATCH_MS = 120_000L
    private const val MEDIASTORE_REFRESH_GAP = 3000L
    private const val SNAPSHOT_SAVE_DELAY = 2000L
    private const val MAX_WATCHES = 2500
    private const val TOMBSTONE_TTL = 20_000L
    private const val DATE_TAKEN_SYNC_PERIOD = 6 * 60 * 60 * 1000L
    private const val SNAPSHOT_MAGIC = 0x47534958
    private const val SNAPSHOT_VERSION = 1

    private val WATCH_MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

    fun interface ChangeListener {
        /** Called on the main thread. An empty set means "something changed, folder unknown". */
        fun onChanged(dirs: Set<String>)
    }

    @Volatile
    private var index = ScanIndex.EMPTY
    private val refreshLock = ReentrantLock()
    private val indexLock = Any()

    // dir -> file name -> time the delete finished (0 while it is still running)
    private val tombstones = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    @Volatile
    private var snapshotFile: File? = null

    @Volatile
    private var lastRefreshAt = 0L

    @Volatile
    private var lastRoots: List<String> = emptyList()

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FastScanner").apply { isDaemon = true }
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = ConcurrentHashMap<Any, ChangeListener>()
    private val dirtyDirs = ConcurrentHashMap.newKeySet<String>()
    private val flushScheduled = AtomicBoolean(false)
    private val savePending = AtomicBoolean(false)

    @Volatile
    private var mediaStoreDirty = false

    @Volatile
    private var suppressMediaStoreUntil = 0L

    private val watchLock = Any()
    private var foregroundCount = 0
    private var backgroundSince = 0L
    private var stopTask: ScheduledFuture<*>? = null

    /** How long the app was out of sight before the screen that is visible now came back (huge on a cold start). */
    @Volatile
    var lastAwayMs = Long.MAX_VALUE
        private set
    @Volatile
    private var watching = false
    private val syncLock = Any()
    private val syncScheduled = AtomicBoolean(false)

    // creating watchers takes long, it must never delay the worker that handles changes or the screens
    private val watchWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FastScannerWatch").apply { isDaemon = true }
    }
    @Volatile
    private var appContext: Context? = null
    private var contentObserver: ContentObserver? = null
    private val watchers = HashMap<String, FileObserver>()

    fun isEnabled(context: Context): Boolean {
        val config = context.config
        if (!config.useFastScanner || config.filterMedia and TYPE_PORTRAITS != 0) {
            return false
        }

        return !isRPlus() || isExternalStorageManager()
    }

    fun getPollPeriod(context: Context) = if (isEnabled(context)) POLL_PERIOD else DEFAULT_POLL_PERIOD

    /** Loads the saved index and walks the storage while the rest of the app is still starting up. */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        worker.execute {
            try {
                resumePendingDeletes(appContext)
            } catch (ignored: Throwable) {
            }

            try {
                if (isEnabled(appContext)) {
                    refresh(appContext, false)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun ensureInit(context: Context) {
        if (snapshotFile == null) {
            synchronized(indexLock) {
                if (snapshotFile == null) {
                    val file = File(context.applicationContext.filesDir, "scan_index.bin")
                    if (index.dirs.isEmpty()) {
                        loadSnapshot(file)?.let { index = it }
                    }
                    snapshotFile = file
                }
            }
        }
    }

    private fun getRoots(context: Context): List<String> {
        val roots = LinkedHashSet<String>()
        roots.add(context.internalStoragePath)
        val sdCard = context.sdCardPath
        if (sdCard.isNotEmpty() && File(sdCard).exists()) {
            roots.add(sdCard)
        }

        val otgPath = context.config.OTGPath
        context.config.includedFolders.forEach {
            if (it.isNotEmpty() && (otgPath.isEmpty() || !it.startsWith(otgPath))) {
                roots.add(it)
            }
        }
        return roots.filter { it.isNotEmpty() }
    }

    // ---- scanning ----------------------------------------------------------------------

    /** Walks the storage and updates the index. Returns the number of folders that were listed again or removed. */
    fun refresh(context: Context, forceFull: Boolean = false): Int {
        ensureInit(context)
        val roots = getRoots(context)
        val count = refreshLock.withLock {
            val old = index
            val result = Walker.walk(old, roots, true, forceFull)
            // every readable root yields at least its own node: an empty result means the storage could not be
            // read (not ready, unmounted), keep what is known instead of wiping it
            if (result.dirs.isEmpty() && old.dirs.isNotEmpty()) {
                return@withLock 0
            }

            synchronized(indexLock) {
                index = ScanIndex(result.dirs)
            }
            lastRoots = roots
            lastRefreshAt = System.currentTimeMillis()
            val changed = result.relisted + result.removed
            if (changed > 0) {
                saveAsync()
            }
            changed
        }
        scheduleSyncWatchers()
        return count
    }

    fun refreshIfStale(context: Context) {
        if (System.currentTimeMillis() - lastRefreshAt > REFRESH_MAX_AGE) {
            refresh(context, false)
        }
    }

    /** Cheap single folder refresh. New sub folders are walked as well. Returns true when the content changed. */
    fun refreshDir(context: Context, path: String, ignoreFreshness: Boolean = false): Boolean {
        ensureInit(context)
        val changed = applyDirChanges(listOf(path), true)
        if (changed.isNotEmpty()) {
            scheduleSyncWatchers()
        }
        return changed.isNotEmpty()
    }

    /**
     * Looks at the folders that matter most only: the ones holding media and the top levels of every storage.
     * That is a few hundred timestamps, so it takes milliseconds even on a huge storage, and it catches new
     * downloads, deleted photos and removed albums. New folders buried deeper are found by [refresh].
     * Returns true when something changed.
     */
    fun quickRefresh(context: Context): Boolean {
        val quick = quickRefreshDirs(context)
        val synced = syncWithMediaStore(context)
        return quick.isNotEmpty() || synced.isNotEmpty()
    }

    /**
     * MediaStore hears about new and moved media at once, wherever it lands. Every folder it names that the index
     * does not know the file of is listed again, so a download into a folder buried deeper than the quick refresh
     * looks is found within milliseconds too. Only what changed since the last check is asked for.
     */
    private fun syncWithMediaStore(context: Context): Set<String> {
        val dirs = index.dirs
        if (dirs.isEmpty()) {
            return emptySet()
        }

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(SCAN_PREFS, Context.MODE_PRIVATE)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val stale = HashSet<String>()
        var generation = -1L
        try {
            val mediaOnly = "${Files.FileColumns.MEDIA_TYPE} IN (${Files.FileColumns.MEDIA_TYPE_IMAGE}, ${Files.FileColumns.MEDIA_TYPE_VIDEO})"
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                generation = MediaStore.getGeneration(appContext, MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val last = prefs.getLong(KEY_MS_GENERATION, -1L)
                if (last < 0L) {
                    prefs.edit().putLong(KEY_MS_GENERATION, generation).putLong(KEY_MS_SECONDS, nowSeconds).apply()
                    return emptySet()
                }

                selection = "$mediaOnly AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
                args = arrayOf(last.toString())
            } else {
                val last = prefs.getLong(KEY_MS_SECONDS, -1L)
                if (last < 0L) {
                    prefs.edit().putLong(KEY_MS_SECONDS, nowSeconds).apply()
                    return emptySet()
                }

                selection = "$mediaOnly AND ${Images.Media.DATE_ADDED} >= ?"
                args = arrayOf((last - 5L).toString())
            }

            appContext.contentResolver.query(Files.getContentUri("external"), arrayOf(Images.Media.DATA), selection, args, null)?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(Images.Media.DATA)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex) ?: continue
                    val slash = path.lastIndexOf('/')
                    if (slash <= 0) {
                        continue
                    }

                    val dir = path.substring(0, slash)
                    val name = path.substring(slash + 1)
                    if (classifyMedia(name) == 0) {
                        continue
                    }

                    val node = dirs[dir]
                    if ((node == null || node.find(name) == null) && File(path).exists()) {
                        stale.add(dir)
                    }
                }
            }

            // only remembered once the query worked, so a failed one is simply asked again next time
            val editor = prefs.edit().putLong(KEY_MS_SECONDS, nowSeconds)
            if (generation >= 0L) {
                editor.putLong(KEY_MS_GENERATION, generation)
            }
            editor.apply()
        } catch (ignored: Exception) {
            return emptySet()
        }

        if (stale.isEmpty()) {
            return emptySet()
        }

        val result = applyDirChanges(stale, false)
        if (result.isNotEmpty()) {
            scheduleSyncWatchers()
        }
        return result
    }

    private fun quickRefreshDirs(context: Context): Set<String> {
        ensureInit(context)
        val dirs = index.dirs
        if (dirs.isEmpty()) {
            return emptySet()
        }

        val changed = findChangedDirs(dirs, hotPaths(context, dirs))
        if (changed.isEmpty()) {
            return emptySet()
        }

        val result = applyDirChanges(changed, false)
        if (result.isNotEmpty()) {
            scheduleSyncWatchers()
        }
        return result
    }

    private fun hotPaths(context: Context, dirs: Map<String, DirNode>): List<String> {
        val roots = normalizeRoots(getRoots(context))
        val hot = ArrayList<String>()
        for ((path, node) in dirs) {
            if (node.files.isNotEmpty() || isNearRoot(path, roots)) {
                hot.add(path)
            }
        }
        return hot
    }

    private fun isNearRoot(path: String, roots: List<String>): Boolean {
        for (root in roots) {
            if (path == root) {
                return true
            }

            if (path.length > root.length && path.startsWith(root) && path[root.length] == '/') {
                var depth = 0
                for (i in root.length until path.length) {
                    if (path[i] == '/') {
                        depth++
                    }
                }

                if (depth <= HOT_DEPTH) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Lists the given folders again and updates the index in one step. Folders that are gone are dropped with
     * everything below them, new sub folders are walked. [verify] skips folders whose timestamp did not change.
     * Returns the folders whose content, sub folders or existence changed.
     */
    private fun applyDirChanges(paths: Collection<String>, verify: Boolean): Set<String> {
        if (paths.isEmpty()) {
            return emptySet()
        }

        val known = index.dirs
        val now = System.currentTimeMillis()
        val updated = HashMap<String, DirNode>()
        val gone = LinkedHashSet<String>()
        val newSubs = LinkedHashSet<String>()
        val changed = HashSet<String>()

        for (path in paths) {
            val old = known[path]
            val dir = File(path)
            if (!dir.isDirectory) {
                if (old != null) {
                    gone.add(path)
                    changed.add(path)
                }
                continue
            }

            if (verify && old != null && !old.isRacy && readDirMtime(path) == old.mtime) {
                continue
            }

            val node = listDirectory(path, old, false, now) ?: continue
            updated[path] = node
            val nowSubs = node.subdirs.toHashSet()
            val inAndroid = nameOf(path) == "Android"
            node.subdirs.forEach { sub ->
                val full = joinPath(path, sub)
                // app private storage is never walked, see Walker
                if (!known.containsKey(full) && !(inAndroid && (sub == "data" || sub == "obb"))) {
                    newSubs.add(full)
                }
            }

            old?.subdirs?.forEach { sub ->
                if (!nowSubs.contains(sub)) {
                    gone.add(joinPath(path, sub))
                }
            }

            if (old == null || !sameContent(old, node)) {
                changed.add(path)
            }
        }

        if (updated.isEmpty() && gone.isEmpty()) {
            return emptySet()
        }

        val extra = if (newSubs.isEmpty()) emptyMap() else Walker.walkFresh(newSubs)
        synchronized(indexLock) {
            val copy = HashMap(index.dirs)
            copy.putAll(updated)
            copy.putAll(extra)
            if (gone.isNotEmpty()) {
                val prefixes = gone.map { "$it/" }
                copy.keys.removeAll { key -> gone.contains(key) || prefixes.any { key.startsWith(it) } }
            }
            index = ScanIndex(copy)
        }
        saveAsync()
        return changed
    }

    private fun sameContent(a: DirNode, b: DirNode): Boolean {
        if (a.hasNoMedia != b.hasNoMedia || a.burst != b.burst || !a.subdirs.contentEquals(b.subdirs)) {
            return false
        }

        if (a.files.size != b.files.size) {
            return false
        }

        for (i in a.files.indices) {
            val x = a.files[i]
            val y = b.files[i]
            if (x.name != y.name || x.size != y.size || x.mtime != y.mtime) {
                return false
            }
        }
        return true
    }

    /** Cheap poll: only looks at the folders that matter most, see [quickRefresh]. */
    fun hasChanges(context: Context): Boolean {
        ensureInit(context)
        val dirs = index.dirs
        return findChangedDirs(dirs, hotPaths(context, dirs)).isNotEmpty()
    }

    fun isWarm(context: Context): Boolean {
        ensureInit(context)
        return index.dirs.isNotEmpty()
    }

    /** True once the index is in memory. Never waits for the snapshot to load. */
    fun isLoaded() = index.dirs.isNotEmpty()

    /**
     * Folder summaries straight from MediaStore: one query, no walk of the storage. Used while the folder index is
     * still empty (first start), so the album grid does not wait for the walk. Hidden / .nomedia trees MediaStore
     * never indexes are added when the walk is done.
     */
    fun getBootstrapSummaries(
        context: Context,
        pickImage: Boolean,
        pickVideo: Boolean,
        noMediaFolders: Collection<String>
    ): ArrayList<DirSummaryData> {
        val byDir = HashMap<String, ArrayList<FileEntry>>()
        try {
            val projection = arrayOf(Images.Media.DATA, Images.Media.SIZE, Images.Media.DATE_MODIFIED, Images.Media.DATE_TAKEN)
            val selection = "${Files.FileColumns.MEDIA_TYPE} IN (${Files.FileColumns.MEDIA_TYPE_IMAGE}, ${Files.FileColumns.MEDIA_TYPE_VIDEO})"
            context.contentResolver.query(Files.getContentUri("external"), projection, selection, null, null)?.use { cursor ->
                val dataIndex = cursor.getColumnIndexOrThrow(Images.Media.DATA)
                val sizeIndex = cursor.getColumnIndexOrThrow(Images.Media.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(Images.Media.DATE_MODIFIED)
                val takenIndex = cursor.getColumnIndexOrThrow(Images.Media.DATE_TAKEN)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIndex) ?: continue
                    val slash = path.lastIndexOf('/')
                    if (slash <= 0) {
                        continue
                    }

                    val name = path.substring(slash + 1)
                    val kind = classifyMedia(name)
                    if (kind == 0) {
                        continue
                    }

                    val taken = cursor.getLong(takenIndex)
                    val entry = FileEntry(name, cursor.getLong(sizeIndex), cursor.getLong(modifiedIndex) * 1000L, if (taken > 0) taken else 0L, kind)
                    byDir.getOrPut(path.substring(0, slash)) { ArrayList() }.add(entry)
                }
            }
        } catch (ignored: Exception) {
        }

        val now = System.currentTimeMillis()
        val noMedia = noMediaFolders.toHashSet()
        val dirs = HashMap<String, DirNode>(byDir.size * 2 + noMedia.size)
        byDir.forEach { (dir, files) ->
            files.sortBy { it.name }
            dirs[dir] = DirNode(-1L, now, noMedia.contains(dir), false, emptyArray(), files.toTypedArray())
        }

        // a .nomedia file in a folder without media of its own still hides the folders below it
        noMedia.forEach { dir ->
            if (!dirs.containsKey(dir)) {
                dirs[dir] = DirNode(-1L, now, true, false, emptyArray(), emptyArray())
            }
        }

        val config = context.config
        val wantSize = config.directorySorting and SORT_BY_SIZE != 0
        val query = buildQuery(context, pickImage, pickVideo, wantSize || config.fileLoadingPriority != PRIORITY_SPEED, wantSize)
        return buildSummaries(ScanIndex(dirs), query, getTombstones())
    }

    // ---- queries -----------------------------------------------------------------------

    private fun buildQuery(
        context: Context,
        pickImage: Boolean,
        pickVideo: Boolean,
        skipEmpty: Boolean,
        wantSize: Boolean
    ): ScanQuery {
        val config = context.config
        val excluded: Set<String> = if (config.temporarilyShowExcluded) emptySet() else config.excludedFolders.toHashSet()
        return ScanQuery(
            showHidden = config.shouldShowHidden,
            filterMedia = config.filterMedia,
            pickImage = pickImage,
            pickVideo = pickVideo,
            skipEmpty = skipEmpty,
            wantSize = wantSize,
            excluded = excluded,
            included = config.includedFolders.toHashSet(),
            dirSorting = config.directorySorting,
            defaultSorting = config.sorting,
            sortOverrides = config.getFolderSortingOverrides()
        )
    }

    private fun getTombstones(): Tombstones {
        if (tombstones.isEmpty()) {
            return emptyMap()
        }

        val now = System.currentTimeMillis()
        val result = HashMap<String, Set<String>>()
        val dirIterator = tombstones.entries.iterator()
        while (dirIterator.hasNext()) {
            val (dir, names) = dirIterator.next()
            names.entries.removeAll { it.value != 0L && now - it.value > TOMBSTONE_TTL }
            if (names.isEmpty()) {
                dirIterator.remove()
            } else {
                result[dir] = names.keys.toHashSet()
            }
        }
        return result
    }

    fun getSummaries(context: Context, pickImage: Boolean, pickVideo: Boolean): ArrayList<DirSummaryData> {
        ensureInit(context)
        val config = context.config
        val wantSize = config.directorySorting and SORT_BY_SIZE != 0
        val query = buildQuery(context, pickImage, pickVideo, wantSize || config.fileLoadingPriority != PRIORITY_SPEED, wantSize)
        return buildSummaries(index, query, getTombstones())
    }

    fun getVisibleFolders(context: Context, pickImage: Boolean = false, pickVideo: Boolean = false): ArrayList<String> {
        ensureInit(context)
        val query = buildQuery(context, pickImage, pickVideo, false, false)
        return buildVisibleFolders(index, query, getTombstones())
    }

    fun getNoMediaFolders(context: Context): ArrayList<String>? {
        if (!isWarm(context)) {
            return null
        }

        val result = ArrayList<String>()
        index.dirs.forEach { (path, node) ->
            if (node.hasNoMedia) {
                result.add(path)
            }
        }
        return result
    }

    fun getFolderMedia(
        context: Context,
        folder: String,
        pickImage: Boolean,
        pickVideo: Boolean,
        getProperFileSize: Boolean
    ): ArrayList<Medium> {
        ensureInit(context)
        val skipEmpty = getProperFileSize || context.config.fileLoadingPriority != PRIORITY_SPEED
        val query = buildQuery(context, pickImage, pickVideo, skipEmpty, false)
        val node = index.dirs[folder] ?: return ArrayList()
        val files = visibleFiles(folder, node, query, getTombstones())
        val media = ArrayList<Medium>(files.size)
        for (file in files) {
            media.add(
                Medium(
                    null, file.name, joinPath(folder, file.name), folder, file.mtime,
                    file.takenOrModified(), file.size, file.kind, 0, false, 0L, 0L
                )
            )
        }
        return media
    }

    // ---- deleting ----------------------------------------------------------------------

    /** Direct deletes are only used for primary storage paths the app may write to without SAF. */
    fun canDeleteDirectly(context: Context, paths: List<String>): Boolean {
        if (!isEnabled(context)) {
            return false
        }

        val internal = context.internalStoragePath
        return paths.isNotEmpty() && paths.all { it.startsWith("$internal/") }
    }

    /**
     * Hides the files from every query at once, before any file is touched. This is lock free, so it is safe to call
     * from the main thread; the index itself catches up in the background.
     */
    fun beginDelete(context: Context, paths: List<String>) {
        val byDir = paths.groupBy({ parentOf(it) ?: "" }, { nameOf(it) })
        byDir.forEach { (dir, names) ->
            val map = tombstones.getOrPut(dir) { ConcurrentHashMap() }
            names.forEach { map[it] = 0L }
        }

        val appContext = context.applicationContext
        worker.execute {
            try {
                ensureInit(appContext)
                synchronized(indexLock) {
                    val copy = HashMap(index.dirs)
                    byDir.forEach { (dir, names) ->
                        val node = copy[dir] ?: return@forEach
                        val gone = names.toHashSet()
                        copy[dir] = node.withFiles(node.files.filter { !gone.contains(it.name) }.toTypedArray())
                    }
                    index = ScanIndex(copy)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Returns one result per path, 0 means deleted / moved. */
    fun delete(paths: List<String>, binRoot: String?): IntArray {
        val results = IntArray(paths.size) { -1 }
        val pool = ForkJoinPool(4)
        try {
            pool.submit {
                paths.indices.toList().parallelStream().forEach { i ->
                    results[i] = try {
                        if (binRoot != null) moveToBin(paths[i], binRoot) else deleteFile(paths[i])
                    } catch (e: Exception) {
                        -1
                    }
                }
            }.get()
        } finally {
            pool.shutdown()
        }
        return results
    }

    private fun deleteFile(path: String): Int {
        val file = File(path)
        return if (file.delete() || !file.exists()) 0 else 1
    }

    private fun moveToBin(source: String, binRoot: String): Int {
        val file = File(source)
        if (!file.exists()) {
            return 0
        }

        val destination = File(binRoot.trimEnd('/') + if (source.startsWith("/")) source else "/$source")
        val parent = destination.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            return 5
        }

        if (file.renameTo(destination)) {
            return 0
        }

        return try {
            NioFiles.copy(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (destination.length() != file.length()) {
                destination.delete()
                2
            } else {
                destination.setLastModified(file.lastModified())
                if (!file.delete() && file.exists()) {
                    destination.delete()
                    3
                } else {
                    0
                }
            }
        } catch (e: IOException) {
            destination.delete()
            4
        }
    }

    /** Finished deletes keep their tombstone for a short while, failed ones come back. */
    fun finishDelete(paths: List<String>, codes: IntArray) {
        val now = System.currentTimeMillis()
        val failedDirs = HashSet<String>()
        paths.forEachIndexed { i, path ->
            val dir = parentOf(path) ?: return@forEachIndexed
            val name = nameOf(path)
            if (codes.getOrElse(i) { -1 } == 0) {
                tombstones[dir]?.let { if (it.containsKey(name)) it[name] = now }
            } else {
                tombstones[dir]?.remove(name)
                failedDirs.add(dir)
            }
        }

        if (failedDirs.isNotEmpty()) {
            synchronized(indexLock) {
                val copy = HashMap(index.dirs)
                failedDirs.forEach { dir -> copy[dir]?.let { copy[dir] = it.asStale() } }
                index = ScanIndex(copy)
            }
        }
        saveAsync()
    }

    // ---- delete journal ----------------------------------------------------------------

    private fun journalDir(context: Context) = File(context.applicationContext.filesDir, "pending_deletes")

    /**
     * Writes down what is about to be deleted. When the app is killed halfway, the rest is finished on the next
     * start by [resumePendingDeletes]. Returns the journal to hand to [journalDone].
     */
    fun journalStart(context: Context, paths: List<String>, binRoot: String?): File? {
        return try {
            val dir = journalDir(context)
            dir.mkdirs()
            val file = File(dir, "${System.nanoTime()}.del")
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeUTF(binRoot ?: "")
                out.writeInt(paths.size)
                paths.forEach { out.writeUTF(it) }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun journalDone(file: File?) {
        try {
            file?.delete()
        } catch (ignored: Exception) {
        }
    }

    /** Finishes deletes that were cut short, call from a background thread. */
    fun resumePendingDeletes(context: Context) {
        val appContext = context.applicationContext
        val journals = journalDir(appContext).listFiles() ?: return
        for (journal in journals) {
            try {
                var binRoot = ""
                var paths: List<String> = emptyList()
                DataInputStream(BufferedInputStream(FileInputStream(journal))).use { input ->
                    binRoot = input.readUTF()
                    paths = List(input.readInt()) { input.readUTF() }
                }

                val toBin = binRoot.isNotEmpty()
                val remaining = paths.filter { File(it).exists() }
                if (remaining.isNotEmpty()) {
                    beginDelete(appContext, remaining)
                    val codes = delete(remaining, if (toBin) binRoot else null)
                    finishDelete(remaining, codes)
                }

                try {
                    val now = System.currentTimeMillis()
                    paths.forEach { path ->
                        if (toBin) {
                            // moved into the bin: the database row follows the file
                            if (File(binRoot.trimEnd('/') + if (path.startsWith("/")) path else "/$path").exists()) {
                                appContext.mediaDB.updateDeleted("$RECYCLE_BIN$path", now, path)
                            }
                        } else if (!File(path).exists()) {
                            appContext.deleteDBPath(path)
                        }
                    }
                } catch (ignored: Exception) {
                }

                removeFromMediaStore(appContext, paths)
            } catch (ignored: Exception) {
            }
            journal.delete()
        }
    }

    fun removeDirIfEmpty(path: String) = File(path).delete()

    /** Tells MediaStore the files are gone, in batches instead of one query per file. */
    fun removeFromMediaStore(context: Context, paths: List<String>) {
        if (paths.isEmpty()) {
            return
        }

        suppressMediaStoreUntil = System.currentTimeMillis() + 2500
        val uri = Files.getContentUri("external")
        val leftovers = ArrayList<String>()
        paths.chunked(400).forEach { chunk ->
            try {
                val selection = "${Images.Media.DATA} IN (${chunk.joinToString(",") { "?" }})"
                context.contentResolver.delete(uri, selection, chunk.toTypedArray())
            } catch (e: Exception) {
                leftovers.addAll(chunk)
            }
        }

        if (leftovers.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context.applicationContext, leftovers.toTypedArray(), null, null)
            } catch (ignored: Exception) {
            }
        }
        suppressMediaStoreUntil = System.currentTimeMillis() + 2500
    }

    // ---- date taken --------------------------------------------------------------------

    /**
     * MediaStore knows the EXIF "date taken" of files it indexed. Instead of reading the whole table on every
     * start, do it once in a while in the background and keep the values in the index.
     * Returns how many values changed, call from a background thread.
     */
    fun syncDateTakens(context: Context, force: Boolean): Int {
        val now = System.currentTimeMillis()
        val prefs = context.applicationContext.getSharedPreferences("fast_scanner", Context.MODE_PRIVATE)
        if (!force && now - prefs.getLong("last_date_taken_sync", 0L) < DATE_TAKEN_SYNC_PERIOD) {
            return 0
        }

        ensureInit(context)
        prefs.edit().putLong("last_date_taken_sync", now).apply()
        var changed = 0
        val values = HashMap<String, Long>(4096)

        fun flush() {
            changed += applyDateTakens(values)
            values.clear()
        }

        try {
            val projection = arrayOf(Images.Media.DATA, Images.Media.DATE_TAKEN)
            val selection = "${Images.Media.DATE_TAKEN} > 0"
            context.queryCursor(Files.getContentUri("external"), projection, selection, null) { cursor ->
                val path = cursor.getStringValue(Images.Media.DATA)
                val taken = cursor.getLongValue(Images.Media.DATE_TAKEN)
                if (path != null && taken > 0) {
                    values[path] = taken
                    if (values.size >= 4096) {
                        flush()
                    }
                }
            }
            flush()
        } catch (ignored: Exception) {
        }
        return changed
    }

    private fun applyDateTakens(values: Map<String, Long>): Int {
        var changed = 0
        val idx = index
        values.forEach { (path, taken) ->
            val dir = parentOf(path) ?: return@forEach
            val entry = idx.dirs[dir]?.find(nameOf(path)) ?: return@forEach
            if (entry.taken != taken) {
                entry.taken = taken
                changed++
            }
        }

        if (changed > 0) {
            saveAsync()
        }
        return changed
    }

    // ---- instant change detection ------------------------------------------------------

    fun addChangeListener(owner: Any, listener: ChangeListener) {
        listeners[owner] = listener
    }

    fun removeChangeListener(owner: Any) {
        listeners.remove(owner)
    }

    /**
     * Call from onResume of screens showing folders or media. Watching goes on for a while after the last screen
     * left, so changes made in the meantime are in the index when the app comes back.
     */
    fun onForeground(context: Context) {
        synchronized(watchLock) {
            stopTask?.cancel(false)
            stopTask = null
            foregroundCount++
            if (foregroundCount == 1) {
                lastAwayMs = if (backgroundSince > 0L) System.currentTimeMillis() - backgroundSince else Long.MAX_VALUE
                appContext = context.applicationContext
                if (!watching) {
                    startWatching(context.applicationContext)
                }
            }
        }
    }

    fun onBackground() {
        synchronized(watchLock) {
            foregroundCount = maxOf(0, foregroundCount - 1)
            if (foregroundCount == 0) {
                backgroundSince = System.currentTimeMillis()
                stopTask?.cancel(false)
                stopTask = worker.schedule(Runnable { stopWatchingIfIdle() }, BACKGROUND_WATCH_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun startWatching(context: Context) {
        watching = true
        if (contentObserver == null) {
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    onMediaStoreChanged()
                }
            }

            try {
                context.contentResolver.registerContentObserver(Files.getContentUri("external"), true, observer)
                contentObserver = observer
            } catch (ignored: Exception) {
            }
        }

        scheduleSyncWatchers()
    }

    /** Stops watching unless a screen came back in the meantime. Never holds the lock while watchers are stopped. */
    private fun stopWatchingIfIdle() {
        val toStop = synchronized(watchLock) {
            if (foregroundCount > 0 || !watching) {
                return
            }

            watching = false
            contentObserver?.let {
                try {
                    appContext?.contentResolver?.unregisterContentObserver(it)
                } catch (ignored: Exception) {
                }
            }
            contentObserver = null
            val list = ArrayList(watchers.values)
            watchers.clear()
            list
        }
        toStop.forEach { it.stopWatching() }
    }

    private fun onMediaStoreChanged() {
        if (System.currentTimeMillis() < suppressMediaStoreUntil) {
            return
        }

        mediaStoreDirty = true
        scheduleFlush()
    }

    private fun onDirectoryEvent(dir: String) {
        dirtyDirs.add(dir)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            worker.schedule({
                try {
                    flush()
                } catch (ignored: Throwable) {
                }
            }, DEBOUNCE, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        flushScheduled.set(false)
        val context = appContext ?: return
        val dirs = ArrayList<String>()
        val iterator = dirtyDirs.iterator()
        while (iterator.hasNext()) {
            dirs.add(iterator.next())
            iterator.remove()
        }

        val changed = HashSet<String>()
        val fast = isEnabled(context)
        if (fast && dirs.isNotEmpty()) {
            changed.addAll(applyDirChanges(dirs, true))
            if (changed.isNotEmpty()) {
                scheduleSyncWatchers()
            }
        }

        val unknown = mediaStoreDirty
        if (unknown) {
            mediaStoreDirty = false
            if (fast) {
                changed.addAll(quickRefreshDirs(context))
                changed.addAll(syncWithMediaStore(context))
            }
        }

        if (changed.isNotEmpty() || unknown) {
            val result: Set<String> = if (unknown) emptySet() else changed
            mainHandler.post { listeners.values.forEach { it.onChanged(result) } }
        }

        // something changed that no watcher reported: look at the rest of the storage once in a while
        if (unknown && fast && System.currentTimeMillis() - lastRefreshAt > MEDIASTORE_REFRESH_GAP) {
            if (refresh(context, false) > 0) {
                mainHandler.post { listeners.values.forEach { it.onChanged(emptySet()) } }
            }
        }
    }

    private fun scheduleSyncWatchers() {
        if (syncScheduled.compareAndSet(false, true)) {
            watchWorker.execute {
                syncScheduled.set(false)
                try {
                    syncWatchers()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    /** Watch folders holding media, their parents up to the roots and the top levels of the storage. */
    private fun syncWatchers() {
        val context = appContext ?: return
        synchronized(syncLock) {
            if (!watching || !isEnabled(context)) {
                return
            }

            val dirs = index.dirs
            val roots = lastRoots
            val wanted = LinkedHashSet<String>()
            roots.forEach { root ->
                wanted.add(root)
                val rootDepth = root.count { it == '/' }
                dirs.keys.forEach {
                    if (it.startsWith("$root/") && it.count { c -> c == '/' } - rootDepth <= 2) {
                        wanted.add(it)
                    }
                }
            }

            dirs.forEach { (path, node) ->
                if (node.files.isNotEmpty()) {
                    var current: String? = path
                    while (current != null && dirs.containsKey(current)) {
                        if (!wanted.add(current)) {
                            break
                        }
                        current = parentOf(current)
                    }
                }
            }

            val limited = if (wanted.size > MAX_WATCHES) {
                wanted.sortedBy { path -> path.count { it == '/' } }.take(MAX_WATCHES).toHashSet()
            } else {
                wanted
            }

            // only short looks at the shared map, the slow part (adding and removing watches) runs without the lock
            val toStop = ArrayList<FileObserver>()
            val toStart = ArrayList<String>()
            synchronized(watchLock) {
                val iterator = watchers.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (!limited.contains(entry.key)) {
                        toStop.add(entry.value)
                        iterator.remove()
                    }
                }

                limited.forEach { path ->
                    if (!watchers.containsKey(path)) {
                        toStart.add(path)
                    }
                }
            }

            toStop.forEach { it.stopWatching() }
            for (path in toStart) {
                if (!watching) {
                    break
                }

                val observer = createObserver(path)
                observer.startWatching()
                val kept = synchronized(watchLock) {
                    if (watching) {
                        watchers[path] = observer
                        true
                    } else {
                        false
                    }
                }

                if (!kept) {
                    observer.stopWatching()
                    break
                }
            }
        }
    }

    private fun createObserver(path: String): FileObserver {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DirObserver(path)
        } else {
            LegacyDirObserver(path)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private class DirObserver(private val dir: String) : FileObserver(File(dir), WATCH_MASK) {
        override fun onEvent(event: Int, path: String?) {
            onDirectoryEvent(dir)
        }
    }

    @Suppress("DEPRECATION")
    private class LegacyDirObserver(private val dir: String) : FileObserver(dir, WATCH_MASK) {
        override fun onEvent(event: Int, path: String?) {
            onDirectoryEvent(dir)
        }
    }

    // ---- snapshot ----------------------------------------------------------------------

    private fun saveAsync() {
        val file = snapshotFile ?: return
        if (savePending.compareAndSet(false, true)) {
            worker.schedule({
                savePending.set(false)
                try {
                    saveSnapshot(file, index)
                } catch (ignored: Throwable) {
                }
            }, SNAPSHOT_SAVE_DELAY, TimeUnit.MILLISECONDS)
        }
    }

    private fun saveSnapshot(file: File, snapshot: ScanIndex) {
        val temp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 1 shl 16)).use { out ->
            out.writeInt(SNAPSHOT_MAGIC)
            out.writeInt(SNAPSHOT_VERSION)
            out.writeInt(snapshot.dirs.size)
            for ((path, node) in snapshot.dirs) {
                out.writeUTF(path)
                out.writeLong(node.mtime)
                out.writeLong(node.scannedAt)
                out.writeBoolean(node.hasNoMedia)
                out.writeBoolean(node.burst)
                out.writeInt(node.subdirs.size)
                node.subdirs.forEach { out.writeUTF(it) }
                out.writeInt(node.files.size)
                for (entry in node.files) {
                    out.writeUTF(entry.name)
                    out.writeLong(entry.size)
                    out.writeLong(entry.mtime)
                    out.writeLong(entry.taken)
                    out.writeByte(entry.kind)
                }
            }
        }

        if (!temp.renameTo(file)) {
            temp.delete()
        }
    }

    private fun loadSnapshot(file: File): ScanIndex? {
        if (!file.exists()) {
            return null
        }

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                if (input.readInt() != SNAPSHOT_MAGIC || input.readInt() != SNAPSHOT_VERSION) {
                    return null
                }

                val count = input.readInt()
                val dirs = HashMap<String, DirNode>(count * 2)
                repeat(count) {
                    val path = input.readUTF()
                    val mtime = input.readLong()
                    val scannedAt = input.readLong()
                    val hasNoMedia = input.readBoolean()
                    val burst = input.readBoolean()
                    val subdirs = Array(input.readInt()) { input.readUTF() }
                    val files = Array(input.readInt()) {
                        FileEntry(input.readUTF(), input.readLong(), input.readLong(), input.readLong(), input.readByte().toInt())
                    }
                    dirs[path] = DirNode(mtime, scannedAt, hasNoMedia, burst, subdirs, files)
                }
                ScanIndex(dirs)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Folders that were just emptied by the user and must disappear from lists before the files are gone. */
object PendingRemovals {
    private val paths = ConcurrentHashMap.newKeySet<String>()

    fun add(path: String) {
        paths.add(path.lowercase())
    }

    fun remove(path: String) {
        paths.remove(path.lowercase())
    }

    fun contains(path: String) = paths.isNotEmpty() && paths.contains(path.lowercase())
}
