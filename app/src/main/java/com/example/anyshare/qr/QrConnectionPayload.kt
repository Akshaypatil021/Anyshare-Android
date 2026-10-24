package com.example.anyshare.qr

import org.json.JSONObject

/**
 * Payload encoded inside the QR Code and used for pairing receiver with sender.
 */
data class QrConnectionPayload(
    val ssid: String,
    val passphrase: String,
    val hostIp: String,
    val port: Int = 8888,
    val token: String,
    val pinCode: String = "",
    val deviceName: String = "Anyshare Device"
) {
    /**
     * Convert connection metadata into a compact JSON string.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("ssid", ssid)
        json.put("pass", passphrase)
        json.put("ip", hostIp)
        json.put("port", port)
        json.put("token", token)
        json.put("pin", pinCode)
        json.put("name", deviceName)
        return json.toString()
    }

    companion object {
        /**
         * Parse QR code JSON string back into QrConnectionPayload.
         */
        fun fromJson(jsonString: String): QrConnectionPayload? {
            return try {
                val json = JSONObject(jsonString)
                QrConnectionPayload(
                    ssid = json.optString("ssid", ""),
                    passphrase = json.optString("pass", ""),
                    hostIp = json.optString("ip", "192.168.49.1"),
                    port = json.optInt("port", 8888),
                    token = json.optString("token", ""),
                    pinCode = json.optString("pin", ""),
                    deviceName = json.optString("name", "Unknown Device")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
