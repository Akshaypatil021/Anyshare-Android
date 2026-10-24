package com.example.anyshare.qr

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * CameraX ImageAnalysis Analyzer for real-time QR Code decoding with ZXing.
 */
class QrImageAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    private var isScanningEnabled = true

    fun pauseScanning() {
        isScanningEnabled = false
    }

    fun resumeScanning() {
        isScanningEnabled = true
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!isScanningEnabled) {
            imageProxy.close()
            return
        }

        val buffer: ByteBuffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val width = imageProxy.width
        val height = imageProxy.height

        try {
            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)

            if (result != null && result.text.isNotEmpty()) {
                isScanningEnabled = false // Prevent duplicate detections
                onQrCodeScanned(result.text)
            }
        } catch (_: Exception) {
            // No QR code found in current frame, normal behavior
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
