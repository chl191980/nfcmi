package com.chen.nfcwifidirectshare.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chen.nfcwifidirectshare.nfc.NfcHandshakeCodec
import com.chen.nfcwifidirectshare.nfc.NfcPayloadStore
import com.chen.nfcwifidirectshare.nfc.NfcReader
import com.chen.nfcwifidirectshare.transfer.FileTransferClient
import com.chen.nfcwifidirectshare.transfer.FileTransferServer
import com.chen.nfcwifidirectshare.transfer.NfcSessionParams
import com.chen.nfcwifidirectshare.transfer.OutgoingPayload
import com.chen.nfcwifidirectshare.transfer.PayloadKind
import com.chen.nfcwifidirectshare.transfer.TransferProgress
import com.chen.nfcwifidirectshare.transfer.TransferProtocol
import com.chen.nfcwifidirectshare.transfer.TransferStage
import com.chen.nfcwifidirectshare.wifi.WifiDirectController
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var wifiDirect: WifiDirectController
    private lateinit var sendTextInput: EditText
    private lateinit var selectedFileText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = "selected-file"
    private var selectedFileMime: String = "application/octet-stream"
    private var selectedFileSize: Long = -1L

    private var outgoingPayload: OutgoingPayload? = null
    private var pendingHandshake: NfcSessionParams? = null
    private var transferServer: FileTransferServer? = null
    private var transferClient: FileTransferClient? = null
    private var nfcReader: NfcReader? = null
    private var senderGroupPrepared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirect = WifiDirectController(this, wifiListener)
        setContentView(buildUi())
        ensureRuntimePermissions()
        checkDeviceFeatures()
    }

    override fun onStart() {
        super.onStart()
        wifiDirect.register()
    }

    override fun onStop() {
        nfcReader?.stop()
        wifiDirect.unregister()
        super.onStop()
    }

    override fun onDestroy() {
        transferServer?.stop()
        transferClient?.stop()
        super.onDestroy()
    }

    @Deprecated("Use Activity Result APIs in production; kept dependency-free for this learning project.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            selectedFileUri = uri
            selectedFileMime = contentResolver.getType(uri) ?: "application/octet-stream"
            resolveFileInfo(uri)
            try {
                val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) {
                // Some providers do not grant persistable permissions; immediate transfer still works.
            }
            selectedFileText.text = "已选择：$selectedFileName (${formatBytes(selectedFileSize)})"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                setStatus("权限未完全授予：${denied.joinToString()}。WiFi Direct 发现可能失败。")
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }

        root.addView(title("NFC + WiFi Direct 互传"))
        root.addView(bodyText("发送端先准备内容，再把两台手机 NFC 区域贴近；接收端读到握手后自动连接 WiFi Direct 并接收。"))

        root.addView(sectionTitle("发送端"))
        sendTextInput = EditText(this).apply {
            hint = "输入要发送的文本"
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(sendTextInput, matchWrap())
        root.addView(button("发送文本：准备 NFC 握手") { prepareTextSend() })

        selectedFileText = bodyText("未选择文件")
        root.addView(selectedFileText)
        root.addView(button("选择文件") { pickFile() })
        root.addView(button("发送文件：准备 NFC 握手") { prepareFileSend() })

        root.addView(sectionTitle("接收端"))
        root.addView(button("开始接收：打开 NFC 读卡模式") { startReceiving() })

        root.addView(sectionTitle("传输状态"))
        statusText = bodyText("空闲")
        root.addView(statusText)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        root.addView(progressBar, matchWrap())
        progressText = bodyText("0 B / 0 B")
        root.addView(progressText)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun prepareTextSend() {
        val text = sendTextInput.text.toString()
        if (text.isBlank()) {
            toast("请输入要发送的文本")
            return
        }
        prepareOutgoing(OutgoingPayload.textPayload(text))
    }

    private fun prepareFileSend() {
        val uri = selectedFileUri
        if (uri == null) {
            toast("请先选择文件")
            return
        }
        prepareOutgoing(
            OutgoingPayload.filePayload(
                uri = uri,
                displayName = selectedFileName,
                mimeType = selectedFileMime,
                sizeBytes = selectedFileSize
            )
        )
    }

    private fun prepareOutgoing(payload: OutgoingPayload) {
        if (!ensureRuntimePermissions()) return
        outgoingPayload = payload
        pendingHandshake = null
        senderGroupPrepared = false
        transferServer?.stop()
        transferClient?.stop()
        NfcPayloadStore.clear(this)

        updateProgress(
            TransferProgress(
                stage = TransferStage.PREPARING_WIFI_DIRECT,
                totalBytes = payload.sizeBytes,
                message = "正在创建 WiFi Direct 发送组"
            )
        )

        wifiDirect.removeGroup {
            wifiDirect.createGroup()
        }
    }

    private fun startReceiving() {
        if (!ensureRuntimePermissions()) return
        transferClient?.stop()
        transferServer?.stop()
        outgoingPayload = null
        pendingHandshake = null
        senderGroupPrepared = false
        NfcPayloadStore.clear(this)

        nfcReader?.stop()
        nfcReader = NfcReader(this, object : NfcReader.Listener {
            override fun onHandshake(params: NfcSessionParams) {
                nfcReader?.stop()
                pendingHandshake = params
                updateProgress(
                    TransferProgress(
                        stage = TransferStage.CONNECTING_WIFI_DIRECT,
                        totalBytes = params.sizeBytes,
                        message = "NFC 握手成功，正在连接 ${params.senderName}"
                    )
                )
                wifiDirect.connectToOwner(params.wifiDeviceAddress)
            }

            override fun onError(message: String, throwable: Throwable?) {
                updateProgress(TransferProgress(stage = TransferStage.FAILED, message = message))
            }
        })
        nfcReader?.start()
        updateProgress(
            TransferProgress(
                stage = TransferStage.WAITING_FOR_NFC,
                message = "接收端已开启 NFC 读卡模式，请贴近发送端"
            )
        )
    }

    private fun onSenderGroupReady(group: WifiP2pGroup) {
        val payload = outgoingPayload ?: return
        if (senderGroupPrepared) return

        val deviceAddress = wifiDirect.thisDevice?.deviceAddress
            ?: group.owner?.deviceAddress
            ?: run {
                setStatus("无法取得本机 WiFi Direct deviceAddress，请稍后重试")
                return
            }

        val params = NfcSessionParams(
            sessionId = payload.sessionId,
            token = payload.token,
            senderName = Build.MODEL ?: "Android",
            wifiDeviceAddress = deviceAddress,
            serverPort = TransferProtocol.DEFAULT_PORT,
            payloadKind = payload.kind,
            displayName = payload.displayName,
            mimeType = payload.mimeType,
            sizeBytes = payload.sizeBytes
        )

        NfcPayloadStore.save(this, NfcHandshakeCodec.encode(params))
        transferServer = FileTransferServer(
            context = this,
            payload = payload,
            listener = object : FileTransferServer.Listener {
                override fun onProgress(progress: TransferProgress) = updateProgress(progress)
                override fun onComplete() = toast("发送成功")
                override fun onError(message: String, throwable: Throwable?) {
                    updateProgress(TransferProgress(stage = TransferStage.FAILED, message = message))
                }
            }
        ).also { it.start() }

        senderGroupPrepared = true
        updateProgress(
            TransferProgress(
                stage = TransferStage.WAITING_FOR_NFC,
                totalBytes = payload.sizeBytes,
                message = "发送端已准备好。现在把两台手机 NFC 区域贴近。"
            )
        )
    }

    private fun maybeStartClient(info: WifiP2pInfo) {
        val handshake = pendingHandshake ?: return
        if (!info.groupFormed) return
        val host = info.groupOwnerAddress?.hostAddress ?: return
        if (transferClient != null) return

        transferClient = FileTransferClient(
            context = this,
            hostAddress = host,
            port = handshake.serverPort,
            expectedToken = handshake.token,
            listener = object : FileTransferClient.Listener {
                override fun onProgress(progress: TransferProgress) = updateProgress(progress)

                override fun onComplete(savedPath: String) {
                    runOnUiThread {
                        toast("接收成功")
                        setStatus("接收成功，已保存到：$savedPath")
                    }
                }

                override fun onError(message: String, throwable: Throwable?) {
                    updateProgress(TransferProgress(stage = TransferStage.FAILED, message = message))
                }
            }
        ).also { it.start() }
    }

    private val wifiListener = object : WifiDirectController.Listener {
        override fun onWifiP2pEnabled(enabled: Boolean) {
            if (!enabled) setStatus("WiFi Direct 未启用，请打开 WiFi")
        }

        override fun onThisDeviceChanged(device: WifiP2pDevice) {
            // Useful during real-device debugging: this address is exchanged through NFC.
        }

        override fun onPeersChanged(peers: Collection<WifiP2pDevice>) {
            // Demo UI keeps this silent; add a peer list here if you want manual fallback.
        }

        override fun onConnectionInfo(info: WifiP2pInfo) {
            maybeStartClient(info)
        }

        override fun onGroupInfo(group: WifiP2pGroup) {
            onSenderGroupReady(group)
        }

        override fun onError(message: String) {
            updateProgress(TransferProgress(stage = TransferStage.FAILED, message = message))
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    private fun resolveFileInfo(uri: Uri) {
        selectedFileName = "selected-file"
        selectedFileSize = -1L
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) selectedFileName = it.getString(nameIndex) ?: selectedFileName
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) selectedFileSize = it.getLong(sizeIndex)
            }
        }
        if (selectedFileSize < 0L) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                selectedFileSize = descriptor.length
            }
        }
    }

    private fun ensureRuntimePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return false
        }
        return true
    }

    private fun checkDeviceFeatures() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        when {
            adapter == null -> setStatus("此设备不支持 NFC")
            !adapter.isEnabled -> setStatus("NFC 未开启，请到系统设置中打开")
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            setStatus("此设备不支持 WiFi Direct")
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            setStatus("此设备不支持 NFC HCE，发送端握手不可用")
        }
    }

    private fun updateProgress(progress: TransferProgress) {
        runOnUiThread {
            val total = progress.totalBytes
            val transferred = progress.bytesTransferred
            progressBar.progress = if (total > 0L) {
                ((transferred * 1000L) / total).coerceIn(0L, 1000L).toInt()
            } else if (progress.stage == TransferStage.COMPLETED) {
                1000
            } else {
                0
            }

            statusText.text = progress.message.ifBlank { progress.stage.name }
            val speed = if (progress.speedBytesPerSecond > 0L) {
                " · ${formatBytes(progress.speedBytesPerSecond)}/s"
            } else {
                ""
            }
            progressText.text = "${formatBytes(transferred)} / ${formatBytes(total)}$speed"
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.rgb(16, 24, 40))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.rgb(11, 99, 206))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        }
    }

    private fun bodyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(52, 64, 84))
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, dp(8))
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "未知大小"
        val unit = 1024.0
        return when {
            bytes >= unit * unit * unit -> String.format(Locale.US, "%.2f GB", bytes / unit / unit / unit)
            bytes >= unit * unit -> String.format(Locale.US, "%.2f MB", bytes / unit / unit)
            bytes >= unit -> String.format(Locale.US, "%.1f KB", bytes / unit)
            else -> "$bytes B"
        }
    }

    companion object {
        private const val REQUEST_PICK_FILE = 1001
        private const val REQUEST_PERMISSIONS = 1002
    }
}
