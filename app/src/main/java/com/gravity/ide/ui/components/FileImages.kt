package com.gravity.ide.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures from files, decoded away from the screen's thread and only as big as they are shown
 * (a 12-megapixel photo decoded in full on the screen's thread made lists stutter and could run
 * out of memory). Recently shown pictures are kept in memory, so scrolling back is instant.
 */
object FileImages {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(path: String, maxSide: Int): String {
        val f = File(path)
        return "$path:${f.length()}:${f.lastModified()}:$maxSide"
    }

    /** The picture if it is already decoded at this size. */
    fun cached(path: String, maxSide: Int): ImageBitmap? = cache.get(key(path, maxSide))

    /** Decodes [path] so its longer side is about [maxSide] pixels (at least); null if it is not a picture. */
    fun load(path: String, maxSide: Int): ImageBitmap? {
        val k = key(path, maxSide)
        cache.get(k)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull() ?: return null
        return bmp.asImageBitmap().also { cache.put(k, it) }
    }
}

/** The picture in [path] at about [maxSide] pixels, decoded in the background (null until ready). */
@Composable
fun rememberFileImage(path: String?, maxSide: Int = 1024): ImageBitmap? {
    val state = produceState(initialValue = path?.let { FileImages.cached(it, maxSide) }, path, maxSide) {
        value = if (path == null) null else withContext(Dispatchers.IO) { runCatching { FileImages.load(path, maxSide) }.getOrNull() }
    }
    return state.value
}
