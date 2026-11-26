package org.fossify.gallery

import com.github.ajalt.reprint.core.Reprint
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import org.fossify.commons.FossifyApp
import org.fossify.gallery.databases.GalleryDatabase
import org.fossify.gallery.helpers.DirectoryCache
import org.fossify.gallery.helpers.FastScanner

class App : FossifyApp() {

    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
        DirectoryCache.prewarm(this)
        FastScanner.prewarm(this)
        // open the database and run its migrations now, so the first screen does not wait for it
        Thread {
            try {
                GalleryDatabase.getInstance(this).openHelper.writableDatabase
            } catch (ignored: Throwable) {
            }
        }.start()
    }
}
