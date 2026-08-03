package com.chl.nfcmi.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable

class WifiDirectController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onWifiP2pEnabled(enabled: Boolean)
        fun onThisDeviceChanged(device: WifiP2pDevice)
        fun onPeersChanged(peers: Collection<WifiP2pDevice>)
        fun onConnectionInfo(info: WifiP2pInfo)
        fun onGroupInfo(group: WifiP2pGroup)
        fun onError(message: String)
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, Looper.getMainLooper()) {
        listener.onError("WiFi Direct channel 已断开，请重启应用后再试")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    var thisDevice: WifiP2pDevice? = null
        private set

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    listener.onWifiP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.parcelableExtraCompat<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        requestConnectionInfo()
                        requestGroupInfo()
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = intent.parcelableExtraCompat<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    if (device != null) {
                        thisDevice = device
                        listener.onThisDeviceChanged(device)
                    }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, intentFilter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager.discoverPeers(channel, actionListener("发现附近 WiFi Direct 设备") {
            requestPeers()
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        manager.createGroup(channel, actionListener("创建 WiFi Direct 组") {
            requestGroupInfo()
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToOwner(ownerDeviceAddress: String) {
        discoverPeers()
        mainHandler.postDelayed({
            val config = WifiP2pConfig().apply {
                deviceAddress = ownerDeviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            manager.connect(channel, config, actionListener("连接发送端 WiFi Direct 组"))
        }, 1_200L)
    }

    fun removeGroup(onRemoved: (() -> Unit)? = null) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onRemoved?.invoke()
            }

            override fun onFailure(reason: Int) {
                if (onRemoved != null) {
                    // Preparing a new send session only needs best-effort cleanup.
                    onRemoved.invoke()
                } else {
                    listener.onError("移除旧 WiFi Direct 组失败：${reasonToText(reason)}")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        manager.requestPeers(channel) { peers: WifiP2pDeviceList ->
            listener.onPeersChanged(peers.deviceList)
        }
    }

    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            listener.onConnectionInfo(info)
        }
    }

    private fun requestGroupInfo() {
        manager.requestGroupInfo(channel) { group ->
            if (group != null) {
                listener.onGroupInfo(group)
            }
        }
    }

    private fun actionListener(action: String, onSuccess: () -> Unit = {}): WifiP2pManager.ActionListener {
        return object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onSuccess()
            }

            override fun onFailure(reason: Int) {
                listener.onError("$action 失败：${reasonToText(reason)}")
            }
        }
    }

    private fun reasonToText(reason: Int): String {
        return when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 WiFi Direct"
            WifiP2pManager.BUSY -> "系统 WiFi Direct 正忙，请稍后重试"
            WifiP2pManager.ERROR -> "系统返回通用错误"
            else -> "未知错误码 $reason"
        }
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }
}
