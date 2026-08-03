package com.chl.nfcmi.transfer

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

object TransferProtocol {
    const val DEFAULT_PORT = 8988
    private const val MAX_HEADER_BYTES = 64 * 1024

    fun writeHeader(out: DataOutputStream, metadata: TransferMetadata) {
        val header = metadata.toJson().toString().toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES) { "Header is too large: ${header.size}" }
        out.writeInt(header.size)
        out.write(header)
        out.flush()
    }

    fun readHeader(input: DataInputStream): TransferMetadata {
        val headerSize = input.readInt()
        require(headerSize in 1..MAX_HEADER_BYTES) { "Invalid header size: $headerSize" }
        val bytes = ByteArray(headerSize)
        input.readFully(bytes)
        return TransferMetadata.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    }
}
