package com.d4viddf.hyperbridge.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.createBitmap

/**
 * Maximum safe dimension for island notification icons and pictures (256x256 px).
 * Prevents TransactionTooLargeException (~1MB shared Binder buffer limit).
 */
const val MAX_SAFE_ISLAND_BITMAP_DIMENSION = 256

fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && this.bitmap != null) {
        return this.bitmap
    }

    val width = if (intrinsicWidth > 0) intrinsicWidth else 1
    val height = if (intrinsicHeight > 0) intrinsicHeight else 1

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}

/**
 * Downscales a [Bitmap] so that neither width nor height exceeds [maxDimension] pixels,
 * preserving the aspect ratio.
 *
 * This downscale is essential to prevent Binder [android.os.TransactionTooLargeException]
 * (~1MB transaction buffer limit) when passing bitmaps via IPC to SystemUI for HyperOS Dynamic Island.
 *
 * If [recycleOriginal] is true and a new scaled bitmap is created, the original bitmap is recycled.
 */
fun Bitmap.downscaleSafe(
    maxDimension: Int = MAX_SAFE_ISLAND_BITMAP_DIMENSION,
    recycleOriginal: Boolean = true
): Bitmap {
    if (isRecycled) return this
    if (width <= maxDimension && height <= maxDimension) {
        return this
    }

    val aspectRatio = width.toFloat() / height.toFloat()
    val (targetWidth, targetHeight) = if (width >= height) {
        val tw = maxDimension
        val th = (maxDimension / aspectRatio).toInt().coerceAtLeast(1)
        tw to th
    } else {
        val th = maxDimension
        val tw = (maxDimension * aspectRatio).toInt().coerceAtLeast(1)
        tw to th
    }

    Log.d("ImageExtensions", "Downscaling bitmap from ${width}x${height} to ${targetWidth}x${targetHeight} (max: $maxDimension)")
    val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)

    if (recycleOriginal && scaled !== this && !this.isRecycled) {
        Log.d("ImageExtensions", "Recycling original large bitmap (${width}x${height}) after downscale")
        recycle()
    }

    return scaled
}