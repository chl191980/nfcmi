# NFC WiFi Direct Share

一个从零搭建的 Kotlin Android 学习项目：两台 Android 手机通过 NFC 触碰完成握手，再用 WiFi Direct 建立点对点链路传输文本或文件。

## 技术架构

本项目采用“轻握手 + 重传输”的设计：

1. 发送端选择文本或文件。
2. 发送端创建 WiFi Direct Group Owner，并启动本地 TCP ServerSocket。
3. 发送端通过 NFC HCE 暴露一段 NDEF 握手消息，里面包含 sessionId、token、WiFi Direct deviceAddress、端口和文件元数据。
4. 接收端开启 NFC ReaderMode，贴近发送端后读取 NDEF 握手消息。
5. 接收端根据握手参数连接发送端 WiFi Direct 组。
6. 接收端通过 TCP socket 接收真实文件内容，并保存到应用专属下载目录。

> 重要说明：Android 10/API 29 以后 Android Beam 已废弃，很多新系统和厂商 ROM 上 P2P NDEF Push 不可靠或不可用。因此本项目没有使用 `setNdefPushMessageCallback`、`createNdefMessageCallback`、`onNdefPushComplete` 这类 Beam 路径，而是使用 HCE + ReaderMode 自建 NFC 握手，同时仍然用 `NdefMessage`/`NdefRecord` 作为握手数据格式。

## 文件结构

```text
NfcWifiDirectShare/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/chen/nfcwifidirectshare/
│       │   ├── nfc/
│       │   │   ├── HceHandshakeService.kt
│       │   │   ├── NfcHandshakeCodec.kt
│       │   │   ├── NfcPayloadStore.kt
│       │   │   └── NfcReader.kt
│       │   ├── transfer/
│       │   │   ├── FileTransferClient.kt
│       │   │   ├── FileTransferServer.kt
│       │   │   ├── IncomingFileWriter.kt
│       │   │   ├── TransferModels.kt
│       │   │   └── TransferProtocol.kt
│       │   ├── ui/
│       │   │   └── MainActivity.kt
│       │   └── wifi/
│       │       └── WifiDirectController.kt
│       └── res/
│           ├── values/
│           │   ├── strings.xml
│           │   └── styles.xml
│           └── xml/
│               └── apdu_service.xml
```

## 构建环境

- Android Gradle Plugin: `9.3.0`
- Gradle: 建议 `9.5.0`
- JDK: `17`
- compileSdk/targetSdk: `37`
- minSdk: `23`

如果仓库里还没有 Gradle Wrapper，可以在本机安装 Gradle 后执行：

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew :app:assembleDebug
```

也可以直接用 Android Studio 打开项目，让 IDE 同步 Gradle。

## 权限说明

`AndroidManifest.xml` 已配置：

- `NFC`
- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `CHANGE_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `ACCESS_FINE_LOCATION`
- `NEARBY_WIFI_DEVICES`
- Android 旧版外部存储兼容权限

运行时会请求：

- `ACCESS_FINE_LOCATION`：WiFi Direct 设备发现常见必需项。
- `NEARBY_WIFI_DEVICES`：Android 13/API 33+ 的附近 WiFi 设备权限。

## 运行流程

发送端：

1. 打开 App。
2. 输入文本并点“发送文本：准备 NFC 握手”，或选择文件后点“发送文件：准备 NFC 握手”。
3. 等状态显示“发送端已准备好”。
4. 把手机 NFC 区域贴近接收端。

接收端：

1. 打开 App。
2. 点“开始接收：打开 NFC 读卡模式”。
3. 贴近发送端。
4. 等待 WiFi Direct 连接和 TCP 传输完成。
5. 文件会保存到应用专属目录：`Android/data/com.chen.nfcwifidirectshare/files/Download/received/`。

## 需要真机调优的点

这些点必须用两台 Android 真机测试，模拟器基本测不了：

- NFC HCE 是否被系统路由到本 App。
- 两台手机的 NFC 线圈位置和贴近角度。
- `WifiP2pDevice.deviceAddress` 在目标系统上的可用性。
- `WifiP2pManager.createGroup()` 是否会被系统策略阻止。
- `WifiP2pManager.connect()` 是否弹出系统确认框。
- Android 13+ 的 `NEARBY_WIFI_DEVICES` 和定位开关组合。
- 部分厂商 ROM 对 WiFi Direct 后台/前台限制较多，需要实际机型适配。

## 代码模块说明

- `nfc/HceHandshakeService.kt`：发送端的 Host Card Emulation 服务，接收 SELECT AID 和分片读取 APDU。
- `nfc/NfcReader.kt`：接收端 NFC ReaderMode，读取发送端 HCE 服务返回的 NDEF 握手消息。
- `nfc/NfcHandshakeCodec.kt`：把 `NfcSessionParams` 编码成 `NdefMessage`，以及从 NDEF 解码回参数。
- `wifi/WifiDirectController.kt`：封装 WiFi Direct 广播、建组、发现、连接、连接信息回调。
- `transfer/FileTransferServer.kt`：发送端 TCP 服务，发送文本或文件流。
- `transfer/FileTransferClient.kt`：接收端 TCP 客户端，校验 token 后保存文件。
- `transfer/TransferProtocol.kt`：简单传输协议，先发 4 字节 header 长度，再发 JSON header，最后发内容流。
- `ui/MainActivity.kt`：原生 View UI 和整体流程编排。

## 后续可扩展方向

- 增加手动设备列表，NFC 失败时允许手动选择 WiFi Direct peer。
- 用 Foreground Service 承载长文件传输，避免切后台被杀。
- 使用 MediaStore 保存到公开 Downloads。
- 加入 SHA-256 校验，接收完成后校验完整性。
- 支持多文件批量传输和断点续传。
- 增加蓝牙辅助发现，但仍保持 WiFi Direct 传输主链路。

## GitHub README 建议

上传 GitHub 时建议补充：

- 两台测试手机型号、Android 版本、是否需要手动确认 WiFi Direct 配对。
- NFC 触碰成功率和推荐贴近位置。
- 已知问题列表。
- 演示截图或录屏。
- 架构图：NFC 只传握手参数，WiFi Direct/TCP 传真实内容。
