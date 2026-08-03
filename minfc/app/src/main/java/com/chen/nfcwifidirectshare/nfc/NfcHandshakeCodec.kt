package com.chen.nfcwifidirectshare.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.chen.nfcwifidirectshare.transfer.NfcSessionParams
import org.json.JSONObject

object NfcHandshakeCodec {
    const val MIME_TYPE = "application/vnd.com.chen.nfcwifidirectshare.handshake"

    fun encode(params: NfcSessionParams): ByteArray {
        val json = params.toJson().toString().toByteArray(Charsets.UTF_8)
        val record = NdefRecord.createMime(MIME_TYPE, json)
        return NdefMessage(arrayOf(record)).toByteArray()
    }

    fun decode(bytes: ByteArray): NfcSessionParams {
        val message = NdefMessage(bytes)
        val record = message.records.firstOrNull { it.toMimeType() == MIME_TYPE }
            ?: error("NFC 消息中没有找到握手 MIME 记录")
        val jsonText = String(record.payload, Charsets.UTF_8)
        return NfcSessionParams.fromJson(JSONObject(jsonText))
    }
}
