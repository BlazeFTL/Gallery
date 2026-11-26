package org.fossify.gallery.helpers

import android.content.Context
import org.fossify.gallery.extensions.config
import org.fossify.gallery.models.Directory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Snapshot of the folder list that was last shown. It is read from a small file instead of the database, so the
 * album grid can be painted right after launch while the scan runs in the background.
 */
object DirectoryCache {
    private const val FILE_NAME = "directories.snapshot"
    private const val MAGIC = 0x44495243
    private const val VERSION = 1

    private class Snapshot(val signature: String, val dirs: List<Directory>)

    @Volatile
    private var memory: Snapshot? = null

    @Volatile
    private var lastSavedHash = 0

    private val fileLock = Any()

    /** Everything that decides which folders are listed. */
    fun signature(context: Context, imagesOnly: Boolean, videosOnly: Boolean): String {
        val config = context.config
        return listOf(
            config.shouldShowHidden,
            config.temporarilyShowExcluded,
            config.excludedFolders.hashCode(),
            config.includedFolders.hashCode(),
            config.filterMedia,
            config.showRecycleBinAtFolders,
            imagesOnly,
            videosOnly
        ).joinToString("|")
    }

    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        Thread {
            try {
                load(appContext)
            } catch (ignored: Throwable) {
            }
        }.start()
    }

    fun get(context: Context, signature: String): ArrayList<Directory>? {
        var snapshot = memory
        if (snapshot == null) {
            snapshot = try {
                load(context.applicationContext)
            } catch (ignored: Throwable) {
                null
            }
        }

        if (snapshot == null || snapshot.signature != signature || snapshot.dirs.isEmpty()) {
            return null
        }

        return ArrayList(snapshot.dirs.map { it.copy() })
    }

    fun put(context: Context, signature: String, dirs: List<Directory>) {
        val copies = dirs.map { it.copy() }
        memory = Snapshot(signature, copies)

        val hash = signature.hashCode() * 31 + copies.hashCode()
        if (hash == lastSavedHash) {
            return
        }
        lastSavedHash = hash

        val appContext = context.applicationContext
        Thread {
            try {
                write(appContext, signature, copies)
            } catch (ignored: Throwable) {
            }
        }.start()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): Snapshot? = synchronized(fileLock) {
        memory ?: readFile(context)
    }

    private fun readFile(context: Context): Snapshot? {
        val source = file(context)
        if (!source.exists()) {
            return null
        }

        val snapshot = DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                null
            } else {
                val signature = input.readUTF()
                val count = input.readInt()
                val dirs = ArrayList<Directory>(count)
                repeat(count) {
                    val path = input.readUTF()
                    val tmb = input.readUTF()
                    val name = input.readUTF()
                    val mediaCnt = input.readInt()
                    val modified = input.readLong()
                    val taken = input.readLong()
                    val size = input.readLong()
                    val location = input.readInt()
                    val types = input.readInt()
                    val sortValue = input.readUTF()
                    val subfoldersCount = input.readInt()
                    val subfoldersMediaCount = input.readInt()
                    val containsMediaFilesDirectly = input.readBoolean()
                    dirs.add(
                        Directory(
                            null, path, tmb, name, mediaCnt, modified, taken, size, location, types, sortValue,
                            subfoldersCount, subfoldersMediaCount, containsMediaFilesDirectly
                        )
                    )
                }
                Snapshot(signature, dirs)
            }
        }

        if (snapshot != null) {
            memory = snapshot
        }
        return snapshot
    }

    private fun write(context: Context, signature: String, dirs: List<Directory>) {
        synchronized(fileLock) {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeUTF(signature)
                output.writeInt(dirs.size)
                dirs.forEach {
                    output.writeUTF(it.path)
                    output.writeUTF(it.tmb)
                    output.writeUTF(it.name)
                    output.writeInt(it.mediaCnt)
                    output.writeLong(it.modified)
                    output.writeLong(it.taken)
                    output.writeLong(it.size)
                    output.writeInt(it.location)
                    output.writeInt(it.types)
                    output.writeUTF(it.sortValue)
                    output.writeInt(it.subfoldersCount)
                    output.writeInt(it.subfoldersMediaCount)
                    output.writeBoolean(it.containsMediaFilesDirectly)
                }
            }

            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }
}
