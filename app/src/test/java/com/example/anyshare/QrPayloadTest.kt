package com.example.anyshare

import com.example.anyshare.qr.QrConnectionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QrPayloadTest {

    @Test
    fun testPayloadSerializationAndDeserialization() {
        val original = QrConnectionPayload(
            ssid = "DIRECT-xy-Anyshare",
            passphrase = "superSecretPassword123",
            hostIp = "192.168.49.1",
            port = 8888,
            token = "tok_8821",
            pinCode = "4821",
            deviceName = "Pixel 8 Pro"
        )

        val json = original.toJson()
        val parsed = QrConnectionPayload.fromJson(json)

        assertNotNull(parsed)
        assertEquals("DIRECT-xy-Anyshare", parsed!!.ssid)
        assertEquals("superSecretPassword123", parsed.passphrase)
        assertEquals("192.168.49.1", parsed.hostIp)
        assertEquals(8888, parsed.port)
        assertEquals("tok_8821", parsed.token)
        assertEquals("4821", parsed.pinCode)
        assertEquals("Pixel 8 Pro", parsed.deviceName)
    }

    @Test
    fun testCorruptPayloadHandling() {
        val corruptedJson = "{ not a valid json }"
        val result = QrConnectionPayload.fromJson(corruptedJson)
        assertEquals(null, result)
    }
}
