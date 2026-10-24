package com.example.anyshare.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Utility to generate crisp, high-contrast QR Code bitmaps using ZXing.
 */
object QrCodeGenerator {

    /**
     * Generates a QR Code Bitmap from the provided content string.
     *
     * @param content The text/JSON to encode.
     * @param size Width and height in pixels (default 512).
     * @param foregroundColor Color for the dark QR modules.
     * @param backgroundColor Color for the light background.
     * @return Android Bitmap of the QR code, or null on error.
     */
    fun generateQrBitmap(
        content: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        if (content.isEmpty()) return null

        return try {
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H) // High error correction
                put(EncodeHintType.MARGIN, 1) // Clean minimal margin
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
