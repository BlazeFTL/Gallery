package org.fossify.gallery.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.gallery.extensions.updateDirectoryPath
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.FastScanner

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ensureBackgroundThread {
            if (FastScanner.isEnabled(context)) {
                // the native index is warmed in one pass, folder lists are built from it when the app opens
                FastScanner.refresh(context)
                return@ensureBackgroundThread
            }

            MediaFetcher(context).getFoldersToScan().forEach {
                context.updateDirectoryPath(it)
            }
        }
    }
}
