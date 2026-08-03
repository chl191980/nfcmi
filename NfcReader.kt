package com.chl.nfcmi.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.chl.nfcmi.transfer.NfcSessionParams
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class NfcReader(
    private val activity: Activity,
    private val listener: Listener
) {
    interface Listener {
        fun onHandshake(params: NfcSessionParams)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    fun start() {
        val nfcAdapter = adapter
        if (nfcAdapter == null) {
            listener.onError("此设备不支持 NFC")
            return
        }
        if (!nfcAdapter.isEnabled) {
            listener.onError("请先在系统设置中打开 NFC")
            return
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        nfcAdapter.enableReaderMode(activity, { tag -> readTag(tag) }, flags, null)
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    private fun readTag(tag: Tag) {
        try {
            val isoDep = IsoDep.get(tag) ?: error("未发现 IsoDep NFC 目标")
            isoDep.use {
                it.timeout = 5_000
                it.connect()

                val selected = it.transceive(HceHandshakeService.SELECT_APDU)
                require(isStatusOk(selected)) {
                    "发送端 HCE AID 选择失败，响应=${selected.toHexString()}。请确认发送端已点“准备 NFC 握手”，并保持亮屏解锁。"
                }

                val payload = readNdefBytes(it)
                val params = NfcHandshakeCodec.decode(payload)
                activity.runOnUiThread {
                    listener.onHandshake(params)
                }
            }
        } catch (t: Throwable) {
            activity.runOnUiThread {
                listener.onError("NFC 握手读取失败：${t.message}", t)
            }
        }
    }

    private fun readNdefBytes(isoDep: IsoDep): ByteArray {
        val output = ByteArrayOutputStream()
        var expectedSize = -1
        var chunkIndex = 0

        while (expectedSize < 0 || output.size() < expectedSize) {
            val response = isoDep.transceive(HceHandshakeService.readCommand(chunkIndex))
            require(isStatusOk(response)) { "读取 NFC 分片失败，index=$chunkIndex" }
            require(response.size >= 6) { "NFC 分片响应过短" }

            val body = response.copyOf(response.size - 2)
            val totalSize = ByteBuffer.wrap(body, 0, Int.SIZE_BYTES).int
            require(totalSize >= 0) { "NFC 总长度非法：$totalSize" }
            expectedSize = totalSize

            if (body.size > Int.SIZE_BYTES) {
                output.write(body, Int.SIZE_BYTES, body.size - Int.SIZE_BYTES)
            }

            chunkIndex++
            require(chunkIndex <= 255) { "NFC 握手数据超过单次会话上限" }
        }

        return output.toByteArray().copyOf(expectedSize)
    }

    private fun isStatusOk(response: ByteArray): Boolean {
        return response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02X".format(byte) }
    }
}
