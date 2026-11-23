package org.fossify.gallery.helpers

import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.SORT_BY_DATE_MODIFIED
import org.fossify.commons.helpers.SORT_BY_NAME
import org.fossify.commons.helpers.SORT_BY_PATH
import org.fossify.commons.helpers.SORT_BY_RANDOM
import org.fossify.commons.helpers.SORT_BY_SIZE
import org.fossify.commons.helpers.SORT_DESCENDING
import org.fossify.commons.helpers.SORT_USE_NUMERIC_VALUE
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.RecursiveAction
import java.util.concurrent.atomic.AtomicInteger

internal const val NO_MEDIA_FILE = ".nomedia"

class FileEntry(val name: String, val size: Long, val mtime: Long, @Volatile var taken: Long, val kind: Int) {
    fun takenOrModified() = if (taken > 0) taken else mtime
}

/** One directory as last listed. Only media files are kept, plus the names of sub directories. */
class DirNode(
    val mtime: Long,
    val scannedAt: Long,
    val hasNoMedia: Boolean,
    val burst: Boolean,
    val subdirs: Array<String>,
    val files: Array<FileEntry>
) {
    /**
     * Listed within 2.5s of its last change: another change in the same timestamp tick could have gone
     * unnoticed, so it is listed again next time.
     */
    val isRacy: Boolean get() = mtime < 0 || scannedAt - mtime < 2500

    fun find(name: String): FileEntry? {
        var low = 0
        var high = files.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = files[mid].name.compareTo(name)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return files[mid]
            }
        }
        return null
    }

    fun asStale() = DirNode(-1L, scannedAt, hasNoMedia, burst, subdirs, files)

    fun withFiles(newFiles: Array<FileEntry>) = DirNode(mtime, scannedAt, hasNoMedia, burst, subdirs, newFiles)
}

class ScanIndex(val dirs: Map<String, DirNode>) {
    companion object {
        val EMPTY = ScanIndex(emptyMap())
    }
}

fun classifyMedia(name: String): Int {
    val dot = name.lastIndexOf('.')
    if (dot < 0) {
        return 0
    }

    val length = name.length - dot
    if (length < 4 || length > 5) {
        return 0
    }

    return when (name.substring(dot).lowercase(Locale.ROOT)) {
        ".jpg", ".png", ".jpeg", ".bmp", ".webp", ".heic", ".heif", ".apng", ".avif", ".jxl" -> TYPE_IMAGES
        ".mp4", ".mkv", ".webm", ".avi", ".3gp", ".mov", ".m4v", ".3gpp" -> TYPE_VIDEOS
        ".gif" -> TYPE_GIFS
        ".dng", ".orf", ".nef", ".arw", ".rw2", ".cr2", ".cr3" -> TYPE_RAWS
        ".svg" -> TYPE_SVGS
        else -> 0
    }
}

internal fun parentOf(path: String): String? {
    val i = path.lastIndexOf('/')
    return if (i <= 0) null else path.substring(0, i)
}

internal fun nameOf(path: String) = path.substring(path.lastIndexOf('/') + 1)

internal fun joinPath(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

internal fun normalizeRoots(roots: Collection<String>): List<String> {
    val cleaned = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct().sorted()
    val result = ArrayList<String>()
    for (root in cleaned) {
        if (result.none { root.startsWith("$it/") }) {
            result.add(root)
        }
    }
    return result
}

private fun readAttrs(path: String, followLinks: Boolean): BasicFileAttributes? {
    return try {
        val p = Paths.get(path)
        if (followLinks) {
            Files.readAttributes(p, BasicFileAttributes::class.java)
        } else {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }
}

/**
 * Lists one directory. Metadata of files already known by name is reused unless [forceFull]. Returns null when
 * the directory can not be read.
 */
internal fun listDirectory(path: String, old: DirNode?, forceFull: Boolean, now: Long): DirNode? {
    val dirAttrs = readAttrs(path, true) ?: return null
    if (!dirAttrs.isDirectory) {
        return null
    }

    val mtime = dirAttrs.lastModifiedTime().toMillis()
    val names = File(path).list() ?: return null
    val burstCheck = nameOf(path).startsWith("img_", ignoreCase = true)
    val knownDirs = old?.subdirs?.toHashSet()
    var hasNoMedia = false
    var burst = false
    val subdirs = ArrayList<String>()
    val files = ArrayList<FileEntry>()

    for (name in names) {
        if (burstCheck && name.contains("burst", ignoreCase = true)) {
            burst = true
        }

        if (name == NO_MEDIA_FILE) {
            hasNoMedia = true
            continue
        }

        val kind = classifyMedia(name)
        if (kind == 0) {
            // not media: only directories matter. Known directories are not stat'ed again, a directory that
            // disappeared or turned into a file is dropped when it is visited.
            if (knownDirs != null && knownDirs.contains(name)) {
                subdirs.add(name)
            } else if (readAttrs(joinPath(path, name), false)?.isDirectory == true) {
                subdirs.add(name)
            }
            continue
        }

        if (!forceFull) {
            val previous = old?.find(name)
            if (previous != null) {
                files.add(previous)
                continue
            }
        }

        val attrs = readAttrs(joinPath(path, name), true) ?: continue
        if (attrs.isDirectory) {
            subdirs.add(name)
            continue
        }

        val size = attrs.size()
        val modified = attrs.lastModifiedTime().toMillis()
        val previous = old?.find(name)
        val taken = if (previous != null && previous.size == size && previous.mtime == modified) previous.taken else 0L
        files.add(FileEntry(name, size, modified, taken, kind))
    }

    files.sortBy { it.name }
    subdirs.sort()
    return DirNode(mtime, now, hasNoMedia, burst, subdirs.toTypedArray(), files.toTypedArray())
}

internal class WalkResult(val dirs: Map<String, DirNode>, val relisted: Int, val removed: Int)

/** Parallel, incremental walk: a directory is only listed again when its timestamp changed. */
internal object Walker {
    private const val THREADS = 8

    private class Context(
        val old: ScanIndex,
        val roots: List<String>,
        val skipAndroidData: Boolean,
        val forceFull: Boolean,
        val result: ConcurrentHashMap<String, DirNode>,
        val relisted: AtomicInteger
    )

    private class DirTask(val ctx: Context, val path: String) : RecursiveAction() {
        override fun compute() {
            val node = process(ctx, path) ?: return
            ctx.result[path] = node
            val skipData = ctx.skipAndroidData && nameOf(path) == "Android" && parentOf(path) in ctx.roots
            val children = node.subdirs
                .filter { !(skipData && (it == "data" || it == "obb")) }
                .map { DirTask(ctx, joinPath(path, it)) }
            if (children.isNotEmpty()) {
                ForkJoinTask.invokeAll(children)
            }
        }
    }

    private fun process(ctx: Context, path: String): DirNode? {
        val old = ctx.old.dirs[path]
        if (old != null && !ctx.forceFull && !old.isRacy) {
            val attrs = readAttrs(path, true) ?: return null
            if (attrs.isDirectory && attrs.lastModifiedTime().toMillis() == old.mtime) {
                return old
            }
        }

        val node = listDirectory(path, old, ctx.forceFull, System.currentTimeMillis()) ?: return null
        ctx.relisted.incrementAndGet()
        return node
    }

    fun walk(old: ScanIndex, rawRoots: Collection<String>, skipAndroidData: Boolean, forceFull: Boolean): WalkResult {
        val roots = normalizeRoots(rawRoots)
        val result = ConcurrentHashMap<String, DirNode>()
        val relisted = AtomicInteger()
        val ctx = Context(old, roots, skipAndroidData, forceFull, result, relisted)
        val pool = ForkJoinPool(THREADS)
        try {
            pool.invoke(object : RecursiveAction() {
                override fun compute() {
                    val tasks = roots.map { DirTask(ctx, it) }
                    if (tasks.isNotEmpty()) {
                        ForkJoinTask.invokeAll(tasks)
                    }
                }
            })
        } finally {
            pool.shutdown()
        }

        val removed = old.dirs.keys.count { key ->
            !result.containsKey(key) && roots.any { key == it || key.startsWith("$it/") }
        }
        return WalkResult(result, relisted.get(), removed)
    }

    /** Walks the given directories (and everything below them) without any previous knowledge. */
    fun walkFresh(roots: Collection<String>): Map<String, DirNode> {
        return walk(ScanIndex.EMPTY, roots, false, true).dirs
    }
}

/** True when any known directory differs from the index. Parallel stat of directories only. */
internal fun anyDirectoryChanged(index: ScanIndex): Boolean {
    if (index.dirs.isEmpty()) {
        return false
    }

    val entries = index.dirs.entries.toList()
    val changed = java.util.concurrent.atomic.AtomicBoolean(false)
    val pool = ForkJoinPool(4)
    try {
        pool.submit {
            entries.parallelStream().forEach { (path, node) ->
                if (!changed.get()) {
                    val attrs = readAttrs(path, true)
                    val different = attrs == null || !attrs.isDirectory || node.mtime < 0 ||
                        attrs.lastModifiedTime().toMillis() != node.mtime
                    if (different) {
                        changed.set(true)
                    }
                }
            }
        }.get()
    } finally {
        pool.shutdown()
    }
    return changed.get()
}

// ---- queries ---------------------------------------------------------------------------

class ScanQuery(
    val showHidden: Boolean,
    val filterMedia: Int,
    val pickImage: Boolean,
    val pickVideo: Boolean,
    val skipEmpty: Boolean,
    val wantSize: Boolean,
    val excluded: Set<String>,
    val included: Set<String>,
    val dirSorting: Int,
    val defaultSorting: Int,
    val sortOverrides: Map<String, Int>
)

class DirSummaryData(
    val path: String,
    val count: Int,
    val types: Int,
    val hidden: Boolean,
    val size: Long,
    val modified: Long,
    val taken: Long,
    val minModified: Long,
    val maxModified: Long,
    val minTaken: Long,
    val maxTaken: Long,
    val cover: String
)

internal typealias Tombstones = Map<String, Set<String>>

internal fun filePasses(file: FileEntry, q: ScanQuery): Boolean {
    val kind = file.kind
    if (kind == TYPE_VIDEOS && (q.pickImage || q.filterMedia and TYPE_VIDEOS == 0)) return false
    if (kind == TYPE_IMAGES && (q.pickVideo || q.filterMedia and TYPE_IMAGES == 0)) return false
    if (kind == TYPE_GIFS && q.filterMedia and TYPE_GIFS == 0) return false
    if (kind == TYPE_RAWS && q.filterMedia and TYPE_RAWS == 0) return false
    if (kind == TYPE_SVGS && q.filterMedia and TYPE_SVGS == 0) return false
    if (!q.showHidden && file.name.startsWith(".")) return false
    if (q.skipEmpty && file.size == 0L) return false
    return true
}

private fun isThisOrParentIn(path: String, set: Set<String>): Boolean {
    if (set.isEmpty()) {
        return false
    }

    val withSlash = "$path/"
    return set.any { path.equals(it, true) || withSlash.startsWith("$it/", true) }
}

internal fun hasNoMediaInChain(path: String, index: ScanIndex): Boolean {
    var current: String? = path
    while (current != null) {
        if (index.dirs[current]?.hasNoMedia == true) {
            return true
        }
        current = parentOf(current)
    }
    return false
}

/** Port of String.shouldFolderBeVisible for the filesystem backed case. */
internal fun isDirVisible(path: String, node: DirNode, q: ScanQuery, index: ScanIndex): Boolean {
    if (path.isEmpty()) {
        return false
    }

    val filename = nameOf(path)
    if (filename.startsWith("img_", true) && node.burst) {
        return false
    }

    if (!q.showHidden && filename.startsWith(".")) {
        return false
    } else if (q.included.contains(path)) {
        return true
    }

    val containsNoMedia = if (q.showHidden) false else node.hasNoMedia
    if (!q.showHidden && containsNoMedia) {
        return false
    } else if (q.excluded.contains(path)) {
        return false
    } else if (isThisOrParentIn(path, q.included)) {
        return true
    } else if (isThisOrParentIn(path, q.excluded)) {
        return false
    } else if (!q.showHidden) {
        var bad = containsNoMedia || path.contains("/.")
        if (!bad) {
            var parent = parentOf(path)
            while (parent != null) {
                if (index.dirs[parent]?.hasNoMedia == true) {
                    bad = true
                    break
                }
                parent = parentOf(parent)
            }
        }
        return !bad
    }
    return true
}

private val alphanumeric = org.fossify.commons.helpers.AlphanumericComparator()

/** Ascending comparison matching MediaFetcher.sortMedia. */
internal fun compareEntries(sorting: Int, a: FileEntry, b: FileEntry): Int {
    return when {
        sorting and SORT_BY_NAME != 0 || sorting and SORT_BY_PATH != 0 -> {
            val first = a.name.normalizeString().lowercase(Locale.getDefault())
            val second = b.name.normalizeString().lowercase(Locale.getDefault())
            if (sorting and SORT_USE_NUMERIC_VALUE != 0) alphanumeric.compare(first, second) else first.compareTo(second)
        }
        sorting and SORT_BY_SIZE != 0 -> a.size.compareTo(b.size)
        sorting and SORT_BY_DATE_MODIFIED != 0 -> a.mtime.compareTo(b.mtime)
        else -> a.takenOrModified().compareTo(b.takenOrModified())
    }
}

internal fun visibleFiles(path: String, node: DirNode, q: ScanQuery, tombs: Tombstones): List<FileEntry> {
    val dead = tombs[path]
    return node.files.filter { filePasses(it, q) && (dead == null || !dead.contains(it.name)) }
}

private fun summarize(path: String, node: DirNode, q: ScanQuery, tombs: Tombstones, hidden: Boolean): DirSummaryData? {
    val files = visibleFiles(path, node, q, tombs)
    if (files.isEmpty()) {
        return null
    }

    val sorting = q.sortOverrides[path.lowercase(Locale.getDefault())] ?: q.defaultSorting
    val random = sorting and SORT_BY_RANDOM != 0
    var minFile = files[0]
    var maxFile = files[0]
    var types = 0
    var size = 0L
    var minModified = Long.MAX_VALUE
    var maxModified = Long.MIN_VALUE
    var minTaken = Long.MAX_VALUE
    var maxTaken = Long.MIN_VALUE
    for (file in files) {
        types = types or file.kind
        size += file.size
        minModified = minOf(minModified, file.mtime)
        maxModified = maxOf(maxModified, file.mtime)
        val taken = file.takenOrModified()
        minTaken = minOf(minTaken, taken)
        maxTaken = maxOf(maxTaken, taken)
        if (!random) {
            if (compareEntries(sorting, file, minFile) < 0) minFile = file
            if (compareEntries(sorting, file, maxFile) > 0) maxFile = file
        }
    }

    val first: FileEntry
    val last: FileEntry
    when {
        random -> {
            first = files.first()
            last = files.last()
        }
        sorting and SORT_DESCENDING != 0 -> {
            first = maxFile
            last = minFile
        }
        else -> {
            first = minFile
            last = maxFile
        }
    }

    val dirAscending = q.dirSorting and SORT_DESCENDING == 0
    val modified = if (dirAscending) minOf(first.mtime, last.mtime) else maxOf(first.mtime, last.mtime)
    val taken = if (dirAscending) {
        minOf(first.takenOrModified(), last.takenOrModified())
    } else {
        maxOf(first.takenOrModified(), last.takenOrModified())
    }

    return DirSummaryData(
        path, files.size, types, hidden, if (q.wantSize) size else 0L, modified, taken,
        minModified, maxModified, minTaken, maxTaken, joinPath(path, first.name)
    )
}

internal fun buildSummaries(index: ScanIndex, q: ScanQuery, tombs: Tombstones): ArrayList<DirSummaryData> {
    val result = ArrayList<DirSummaryData>()
    for ((path, node) in index.dirs) {
        if (node.files.isEmpty() || !isDirVisible(path, node, q, index)) {
            continue
        }

        val hidden = hasNoMediaInChain(path, index) && !isThisOrParentIn(path, q.included)
        summarize(path, node, q, tombs, hidden)?.let { result.add(it) }
    }
    return result
}

internal fun buildVisibleFolders(index: ScanIndex, q: ScanQuery, tombs: Tombstones): ArrayList<String> {
    val result = ArrayList<String>()
    for ((path, node) in index.dirs) {
        if (node.files.isNotEmpty() && isDirVisible(path, node, q, index) && visibleFiles(path, node, q, tombs).isNotEmpty()) {
            result.add(path)
        }
    }
    result.sort()
    return result
}
