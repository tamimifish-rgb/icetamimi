package com.tamimi.naqiice.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

private val HINTS = mapOf(DecodeHintType.TRY_HARDER to true)

/**
 * Reads one QR code from a greyscale camera frame (one byte per pixel, row after row).
 * Works at any rotation; tries the inverted picture too. Null when no code is readable.
 */
fun decodeQr(luma: ByteArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || luma.size < width * height) return null
    val source = PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false)
    fun read(s: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(s)), HINTS).text
    } catch (e: ReaderException) { null }
    return read(source) ?: read(source.invert())
}

/**
 * The brightness plane of a camera frame as one tight byte per pixel. Camera rows may be
 * padded ([rowStride] wider than [width]); the padding is dropped.
 */
fun packLuma(plane: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
    val out = ByteArray(width * height)
    val source = plane.duplicate()
    if (rowStride == width) {
        source.position(0)
        source.get(out, 0, minOf(source.remaining(), out.size))
        return out
    }
    for (row in 0 until height) {
        val start = row * rowStride
        if (start >= source.limit()) break
        source.position(start)
        source.get(out, row * width, minOf(width, source.remaining()))
    }
    return out
}
