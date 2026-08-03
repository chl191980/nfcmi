package com.chl.nfcmi.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.nio.ByteBuffer
import kotlin.math.min

class HceHandshakeService : HostApduService() {
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null) {
            return SW_WRONG_LENGTH
        }

        return when {
            isSelectAidCommand(commandApdu) -> ok()
            isReadCommand(commandApdu) -> readChunk(commandApdu)
            else -> SW_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        // Reader moved away or selected another app. Nothing persistent is needed here.
    }

    private fun readChunk(apdu: ByteArray): ByteArray {
        val ndefBytes = NfcPayloadStore.load(this)
            ?: return withStatus("NO_SESSION".toByteArray(Charsets.UTF_8), SW_CONDITIONS_NOT_SATISFIED)

        val chunkIndex = apdu[3].toInt() and 0xFF
        val offset = chunkIndex * CHUNK_SIZE
        val chunk = if (offset < ndefBytes.size) {
            ndefBytes.copyOfRange(offset, min(offset + CHUNK_SIZE, ndefBytes.size))
        } else {
            ByteArray(0)
        }

        // Each response starts with the total NDEF byte length, then a chunk.
        val body = ByteBuffer.allocate(Int.SIZE_BYTES + chunk.size)
            .putInt(ndefBytes.size)
            .put(chunk)
            .array()
        return withStatus(body, SW_OK)
    }

    private fun isReadCommand(apdu: ByteArray): Boolean {
        return apdu.size >= 5 && apdu[0] == 0x00.toByte() && apdu[1] == INS_READ_BINARY
    }

    private fun isSelectAidCommand(apdu: ByteArray): Boolean {
        if (apdu.size < 5) return false
        if (apdu[0] != 0x00.toByte() || apdu[1] != INS_SELECT || apdu[2] != P1_SELECT_BY_NAME) {
            return false
        }

        val aidLength = apdu[4].toInt() and 0xFF
        if (aidLength != AID_BYTES.size || apdu.size < 5 + aidLength) {
            return false
        }

        val requestedAid = apdu.copyOfRange(5, 5 + aidLength)
        return requestedAid.contentEquals(AID_BYTES)
    }

    private fun ok(): ByteArray = SW_OK

    private fun withStatus(body: ByteArray, status: ByteArray): ByteArray {
        return body + status
    }

    companion object {
        private const val CHUNK_SIZE = 220
        private const val AID_HEX = "F0010203040506"
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)
        private val SW_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69.toByte(), 0x85.toByte())
        private val INS_SELECT = 0xA4.toByte()
        private val P1_SELECT_BY_NAME = 0x04.toByte()
        private val INS_READ_BINARY = 0xB0.toByte()
        private val AID_BYTES = hexToBytes(AID_HEX)

        val SELECT_APDU: ByteArray = byteArrayOf(
            0x00,
            INS_SELECT,
            P1_SELECT_BY_NAME,
            0x00,
            AID_BYTES.size.toByte()
        ) + AID_BYTES + byteArrayOf(0x00)

        fun readCommand(chunkIndex: Int): ByteArray {
            require(chunkIndex in 0..255)
            return byteArrayOf(0x00, INS_READ_BINARY, 0x00, chunkIndex.toByte(), 0x00)
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
