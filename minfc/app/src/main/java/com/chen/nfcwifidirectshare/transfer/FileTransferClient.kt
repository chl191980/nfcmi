package com.chen.nfcwifidirectshare.transfer

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max

class FileTransferClient(
    private val context: Context,
    private val hostAddress: String,
    private val port: Int,
    private val expectedToken: String,
    private val listener: Listener
) {
    interface Listener {
        fun onProgress(progress: TransferProgress)
        fun onComplete(savedPath: String)
        fun onError(message: String, throwable: Throwable? = null)
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false

    fun start() {
        executor.execute {
            try {
                Socket().use { socket ->
                    listener.onProgress(
                        TransferProgress(
                            stage = TransferStage.CONNECTING_WIFI_DIRECT,
                            message = "正在连接发送端 $hostAddress:$port"
                        )
                    )
                    socket.connect(InetSocketAddress(hostAddress, port), SOCKET_TIMEOUT_MS)
                    receive(socket)
                }
            } catch (t: Throwable) {
                if (!stopped) {
                    listener.onError("接收失败：${t.message}", t)
                }
            }
        }
    }

    fun stop() {
        stopped = true
        executor.shutdownNow()
    }

    private fun receive(socket: Socket) {
        DataInputStream(BufferedInputStream(socket.getInputStream())).use { input ->
            val metadata = TransferProtocol.readHeader(input)
            require(metadata.token == expectedToken) { "传输令牌不匹配，拒绝接收" }

            val target = IncomingFileWriter.createTarget(context, metadata)
            target.outputStream.use { output ->
                val startedAt = System.currentTimeMillis()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var received = 0L

                while (!stopped && shouldKeepReading(received, metadata.sizeBytes)) {
                    val maxRead = if (metadata.sizeBytes >= 0L) {
                        minOf(buffer.size.toLong(), metadata.sizeBytes - received).toInt()
                    } else {
                        buffer.size
                    }
                    val read = input.read(buffer, 0, maxRead)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    received += read
                    publishProgress(startedAt, received, metadata.sizeBytes, "正在接收 ${metadata.displayName}")
                }
                output.flush()

                require(metadata.sizeBytes < 0L || received == metadata.sizeBytes) {
                    "文件未完整接收：$received/${metadata.sizeBytes} bytes"
                }

                listener.onProgress(
                    TransferProgress(
                        stage = TransferStage.COMPLETED,
                        bytesTransferred = received,
                        totalBytes = metadata.sizeBytes,
                        message = "接收完成"
                    )
                )
                listener.onComplete(target.file.absolutePath)
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

    companion object {
        private const val SOCKET_TIMEOUT_MS = 15_000
    }

    private fun shouldKeepReading(received: Long, total: Long): Boolean {
        return total < 0L || received < total
    }
}
