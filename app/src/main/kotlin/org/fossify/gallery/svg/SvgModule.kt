package org.fossify.gallery.svg

import android.content.Context
import android.graphics.drawable.PictureDrawable

import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG

import java.io.InputStream

@GlideModule
class SvgModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder()).append(InputStream::class.java, SVG::class.java, SvgDecoder())
    }

    // Bigger caches keep album and photo thumbnails ready, so grids paint without decoding the files again.
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setMemorySizeCalculator(
            MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(4f)
                .setBitmapPoolScreens(3f)
                .build()
        )
        builder.setDiskCache(
            InternalCacheDiskCacheFactory(context, InternalCacheDiskCacheFactory.DEFAULT_DISK_CACHE_DIR, 768L * 1024L * 1024L)
        )
    }

    override fun isManifestParsingEnabled() = false
}
