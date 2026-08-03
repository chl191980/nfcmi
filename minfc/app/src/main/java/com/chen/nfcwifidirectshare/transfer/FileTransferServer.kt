package com.chen.nfcwifidirectshare.transfer

import android.content.Context
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

class FileTransferServer(
    private val context: Context,
    private val payload: OutgoingPayload,
    private val port: Int = TransferProtocol.DEFAULT_PORT,
    private val listener: Listener
) {
    interface Listener {
        fun onProgress(progress: TransferProgress)
        fun onComplete()
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        executor.execute {
            try {
                ServerSocket(port).use { server ->
                    serverSocket = server
                    listener.onProgress(
                        TransferProgress(
                            stage = TransferStage.WAITING_FOR_NFC,
                            totalBytes = payload.sizeBytes,
                            message = "发送端已监听端口 $port，等待接收端连接"
                        )
                    )
                    val socket = server.accept()
                    if (!stopped) {
                        sendPayload(socket)
                    }
                }
            } catch (t: Throwable) {
                if (!stopped) {
                    listener.onError("发送端传输失败：${t.message}", t)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
            // Best-effort cleanup.
        }
        executor.shutdownNow()
    }

    private fun sendPayload(socket: Socket) {
        socket.use {
            DataOutputStream(BufferedOutputStream(it.getOutputStream())).use { out ->
                val metadata = TransferMetadata(
                    sessionId = payload.sessionId,
                    token = payload.token,
                    payloadKind = payload.kind,
                    displayName = payload.displayName,
                    mimeType = payload.mimeType,
                    sizeBytes = payload.sizeBytes
                )
                TransferProtocol.writeHeader(out, metadata)

                val startedAt = System.currentTimeMillis()
                var sent = 0L

                when (payload.kind) {
                    PayloadKind.TEXT -> {
                        val bytes = payload.text.orEmpty().toByteArray(Charsets.UTF_8)
                        out.write(bytes)
                        sent = bytes.size.toLong()
                        publishProgress(startedAt, sent, bytes.size.toLong(), "文本发送中")
                    }

                    PayloadKind.FILE -> {
                        val uri = requireNotNull(payload.uri) { "File payload uri is missing" }
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Cannot open selected file input stream" }
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (!stopped) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                sent += read
                                publishProgress(startedAt, sent, payload.sizeBytes, "文件发送中")
                            }
                        }
                    }
                }

                out.flush()
                if (!stopped) {
                    listener.onProgress(
                        TransferProgress(
                            stage = TransferStage.COMPLETED,
                            bytesTransferred = sent,
                            totalBytes = payload.sizeBytes,
                            message = "发送完成"
                        )
                    )
                    listener.onComplete()
                }
            }
        }
    }

    private fun publishProgress(startedAt: Long, bytes: Long, total: Long, message: String) {
        val elapsedSeconds = max(1L, (System.currentTimeMillis() - startedAt) / 1000L)
        listener.onProgress(
            TransferProgress(
                stage = TransferStage.TRANSFERRING,
                bytesTransferred = bytes,
                totalBytes = total,
                speedBytesPerSecond = bytes / elapsedSeconds,
                message = message
            )
        )
    }
}
