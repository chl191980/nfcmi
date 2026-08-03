package com.chl.nfcmi.transfer

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class ReceiveTarget(
    val file: File,
    val outputStream: OutputStream
)

object IncomingFileWriter {
    fun createTarget(context: Context, metadata: TransferMetadata): ReceiveTarget {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "received")
        if (!root.exists()) {
            root.mkdirs()
        }

        val baseName = sanitizeFileName(
            if (metadata.payloadKind == PayloadKind.TEXT) {
                metadata.displayName.ifBlank { "received-text-${metadata.sessionId}.txt" }
            } else {
                metadata.displayName.ifBlank { "received-${metadata.sessionId}.bin" }
            }
        )
        val target = uniqueFile(root, baseName)
        return ReceiveTarget(target, FileOutputStream(target))
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
        return cleaned.ifBlank { "received-file" }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var candidate = File(directory, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base-$index$extension")
            index++
        }
        return candidate
    }
}
