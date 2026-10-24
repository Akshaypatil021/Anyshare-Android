package com.example.anyshare

import com.example.anyshare.transfer.TransferMetricsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferMetricsTest {

    @Test
    fun testPercentageCalculation() {
        val totalBytes = 1000L * 1024 * 1024 // 1000 MB
        val transferred = 500L * 1024 * 1024 // 500 MB

        val calculator = TransferMetricsCalculator(totalBytes, "test.mp4")
        val progress = calculator.update(transferred)

        assertEquals(50, progress.progressPercentage)
        assertEquals("test.mp4", progress.fileName)
    }

    @Test
    fun testByteFormatting() {
        assertEquals("0 B", TransferMetricsCalculator.formatBytes(0))
        assertEquals("500.0 KB", TransferMetricsCalculator.formatBytes(500 * 1024))
        assertEquals("100.0 MB", TransferMetricsCalculator.formatBytes(100L * 1024 * 1024))
        assertEquals("1.5 GB", TransferMetricsCalculator.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testSpeedFormatting() {
        assertEquals("-- MB/s", TransferMetricsCalculator.formatSpeed(0.0))
        assertEquals("500 KB/s", TransferMetricsCalculator.formatSpeed(500.0 * 1024))
        assertEquals("52.4 MB/s", TransferMetricsCalculator.formatSpeed(52.4 * 1024 * 1024))
    }

    @Test
    fun testEtaFormatting() {
        assertEquals("Calculating...", TransferMetricsCalculator.formatEta(0))
        assertEquals("00m 45s left", TransferMetricsCalculator.formatEta(45))
        assertEquals("02m 30s left", TransferMetricsCalculator.formatEta(150))
        assertEquals("01h 05m left", TransferMetricsCalculator.formatEta(3900))
    }
}
