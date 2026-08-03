package com.chen.nfcwifidirectshare.transfer

import android.net.Uri
import org.json.JSONObject
import java.util.UUID

enum class PayloadKind {
    TEXT,
    FILE
}

enum class TransferStage {
    IDLE,
    PREPARING_WIFI_DIRECT,
    WAITING_FOR_NFC,
    CONNECTING_WIFI_DIRECT,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

data class OutgoingPayload(
    val sessionId: String = UUID.randomUUID().toString(),
    val token: String = UUID.randomUUID().toString().replace("-", ""),
    val kind: PayloadKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val text: String? = null,
    val uri: Uri? = null
) {
    companion object {
        fun textPayload(text: String): OutgoingPayload {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return OutgoingPayload(
                kind = PayloadKind.TEXT,
                displayName = "received-text.txt",
                mimeType = "text/plain; charset=utf-8",
                sizeBytes = bytes.size.toLong(),
                text = text
            )
        }

        fun filePayload(uri: Uri, displayName: String, mimeType: String, sizeBytes: Long): OutgoingPayload {
            return OutgoingPayload(
                kind = PayloadKind.FILE,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                uri = uri
            )
        }
    }
}

data class NfcSessionParams(
    val protocolVersion: Int = 1,
    val sessionId: String,
    val token: String,
    val senderName: String,
    val wifiDeviceAddress: String,
    val serverPort: Int,
    val payloadKind: PayloadKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("protocolVersion", protocolVersion)
            .put("sessionId", sessionId)
            .put("token", token)
            .put("senderName", senderName)
            .put("wifiDeviceAddress", wifiDeviceAddress)
            .put("serverPort", serverPort)
            .put("payloadKind", payloadKind.name)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)
    }

    companion object {
        fun fromJson(json: JSONObject): NfcSessionParams {
            return NfcSessionParams(
                protocolVersion = json.optInt("protocolVersion", 1),
                sessionId = json.getString("sessionId"),
                token = json.getString("token"),
                senderName = json.optString("senderName", "Android"),
                wifiDeviceAddress = json.getString("wifiDeviceAddress"),
                serverPort = json.optInt("serverPort", TransferProtocol.DEFAULT_PORT),
                payloadKind = PayloadKind.valueOf(json.getString("payloadKind")),
                displayName = json.optString("displayName", "received-file"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                sizeBytes = json.optLong("sizeBytes", -1L)
            )
        }
    }
}

data class TransferMetadata(
    val protocolVersion: Int = 1,
    val sessionId: String,
    val token: String,
    val payloadKind: PayloadKind,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("protocolVersion", protocolVersion)
            .put("sessionId", sessionId)
            .put("token", token)
            .put("payloadKind", payloadKind.name)
            .put("displayName", displayName)
            .put("mimeType", mimeType)
            .put("sizeBytes", sizeBytes)
    }

    companion object {
        fun fromJson(json: JSONObject): TransferMetadata {
            return TransferMetadata(
                protocolVersion = json.optInt("protocolVersion", 1),
                sessionId = json.getString("sessionId"),
                token = json.getString("token"),
                payloadKind = PayloadKind.valueOf(json.getString("payloadKind")),
                displayName = json.optString("displayName", "received-file"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                sizeBytes = json.optLong("sizeBytes", -1L)
            )
        }
    }
}

data class TransferProgress(
    val stage: TransferStage,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val message: String = ""
)
