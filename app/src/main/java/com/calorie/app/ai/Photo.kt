package com.calorie.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Photo before sending to Claude: EXIF rotation, downscaling and a fresh JPEG.
 * Downscaling saves cost and time (the model shrinks to ~1568 px anyway), the fresh JPEG is for
 * privacy: metadata, including the location, stays on the phone.
 */
object Photo {
    const val MAX_SIDE = 1280
    private const val QUALITY = 85

    fun prepare(ctx: Context, uri: Uri): ByteArray {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("not an image")
        val rotation = cr.openInputStream(uri).use { s -> s?.let { ExifInterface(it).rotationDegrees } ?: 0 }
        val scale = MAX_SIDE.toFloat() / max(raw.width, raw.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val bmp = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }

    /** Decoded bitmap for the preview on the review screen. */
    fun thumbnail(jpeg: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
}
