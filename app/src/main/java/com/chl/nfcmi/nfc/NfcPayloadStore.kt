package com.chl.nfcmi.nfc

import android.content.Context
import android.util.Base64

object NfcPayloadStore {
    private const val PREFS = "nfc_payload_store"
    private const val KEY_NDEF_BYTES = "ndef_message_bytes"

    fun save(context: Context, ndefMessageBytes: ByteArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NDEF_BYTES, Base64.encodeToString(ndefMessageBytes, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NDEF_BYTES, null)
            ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NDEF_BYTES)
            .apply()
    }
}
