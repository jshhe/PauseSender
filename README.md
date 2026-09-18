# PauseSender

简体中文 | [English](README.en-US.md)

PauseSender 可将 Android 手机用作电脑的三键蓝牙 HID 键盘。配对后，悬浮控制条会发送以下按键：

| 悬浮按钮 | 键盘按键 | HID Usage |
| --- | --- | --- |
| 后退 | 左方向键 | `0x50` |
| 暂停/继续 | 空格键 | `0x2C` |
| 前进 | 右方向键 | `0x4F` |

应用包名：`com.pause.sender`

主界面使用 Material 3 组件和开源 Liquid Glass 状态卡片。Android 13 及以上版本会显示实时折射和色散效果；Android 9 至 12 使用不透明的 Material 回退样式。悬浮控制条采用轻量玻璃风格，不会截取其他应用的画面。

## 运行要求

- Android 9（API 28）或更高版本。
- 手机厂商固件开放了 Android 蓝牙 HID Device Profile。
- 电脑支持蓝牙 HID Host；目前主要在 Windows 上测试。

Android 模拟器无法验证真实的蓝牙 HID 链路。有些厂商会关闭 HID Device Profile，即使系统版本较新也可能无法使用。PauseSender 在无法取得系统 Profile 时会显示错误信息。

## 应用语言

安装包内含简体中文和英文资源，不联网也能切换。点按右上角的语言图标，可选择**跟随系统**、**简体中文**或 **English**。系统语言不受支持时，界面回退到简体中文。

切换语言只会重建应用界面，不会断开正在运行的 HID 会话。前台服务通知和悬浮控制的无障碍文案会同步刷新。

## 隐私与权限

PauseSender 不使用无障碍服务，也不申请网络或定位权限。

- `BLUETOOTH_CONNECT`：连接已配对的电脑并发送 HID 报告。
- `BLUETOOTH_ADVERTISE`：配对时让电脑能够发现手机。
- `SYSTEM_ALERT_WINDOW`：在其他应用上层显示三个控制按钮。
- `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_CONNECTED_DEVICE`：使用悬浮控制时保持 HID 注册状态。
- `POST_NOTIFICATIONS`：在 Android 13 及以上版本显示前台服务所需的通知。
- 旧版 `BLUETOOTH` 和 `BLUETOOTH_ADMIN` 权限仅声明到 Android 11（`maxSdkVersion=30`）。

## 与 Windows 配对

1. 安装并打开 PauseSender。
2. 允许附近设备权限，并开启蓝牙。
3. 点按**开始配对**。PauseSender 会先注册键盘描述符，再请求让手机可被发现。
4. 在 Windows 中打开**设置 → 蓝牙和设备 → 添加设备 → 蓝牙**。
5. 选择手机平时使用的蓝牙名称，并在手机和电脑上确认相同的配对码。
6. 在这段 300 秒的配对窗口内，PauseSender 会尝试自动连接刚配对的设备。如果没有连上，请在**已配对设备**下点按对应电脑。
7. 状态显示电脑已连接后，授予**显示在其他应用上层**权限。随后会出现可拖动的后退、暂停/继续和前进控制条。

每次点按都会依次发送一次按下报告和一次全零松开报告，避免按键停留在按下状态。

在应用或前台服务通知中隐藏悬浮控制条时，PauseSender 会先停止接收新的按键操作，释放可能仍处于按下状态的按键，然后断开当前蓝牙 HID Host。服务仍会保留，可重新选择并连接电脑。点按**断开并停止**还会注销 HID 应用并结束前台服务。

## 故障排查

### 电脑找不到手机

- 确认 PauseSender 已显示 HID 键盘注册成功，再在电脑上搜索设备。
- 点按**再次使手机可被发现**。
- 保持 PauseSender 的前台服务通知运行。

### 配对成功，但键盘没有连接

旧配对记录可能把手机识别成了其他设备类型。请在手机和电脑上都删除这条配对记录，先启动 PauseSender 的配对流程，再重新配对。

在 Windows 的**蓝牙和设备**中移除手机。如仍有问题，可在设备管理器中卸载残留的蓝牙设备条目，然后再次配对。

### 应用提示 HID Device Profile 不可用

这通常表示厂商固件没有开放 Android 的公共 `BluetoothHidDevice` API。增加运行时权限、启用无障碍服务或使用无需 Root 的普通应用代码都无法解决。请改用支持该功能的手机或厂商固件。

### 悬浮按钮处于禁用状态

当前 HID 连接已断开。请从已配对设备列表重新连接电脑。在收到连接成功回调前，悬浮控制条可以继续显示，但不会发送按键。

如果之前主动隐藏了悬浮控制条，蓝牙 HID 连接也会随之关闭。请先重新连接电脑，再显示悬浮控制条。

## 构建

仓库中的 Wrapper 使用 Gradle 8.11.1。项目使用 Android Gradle Plugin 8.9.1、Kotlin 2.0.21，`compileSdk=35`、`targetSdk=35`、`minSdk=28`。

在 Windows PowerShell 中运行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
.\gradlew.bat assembleRelease
```

可安装的调试 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

已签名的发布 APK（默认配置签名以便本地安装测试）：

```text
app/build/outputs/apk/release/app-release.apk
```

如需正式生产分发，可在 `app/build.gradle.kts` 中配置自定义的 release 签名密钥。

## 已完成的验证

- 单元测试覆盖空格键、左方向键、右方向键、全零松开报告、描述符范围校验、GET_REPORT 补齐和悬浮窗边界限制。
- 目标架构已配置面向 `arm64-v8a`。
- 仪器测试覆盖中英文资源、带参数的状态消息、英文复数和简体中文回退。
- 自动生成的应用语言配置仅包含 `zh-CN` 和 `en`，App Bundle 会保留两个语言包，支持离线切换。
- Android Lint 未报告问题。
- `aapt2` 已确认包名 `com.pause.sender`、最低 SDK 28、目标 SDK 35、预期的平台权限和 AndroidX 自动生成的应用内签名权限。
- 合并后的 Manifest 不含网络权限或无障碍服务声明。
- Android 15 模拟器已检查 Material 3 明暗主题、沉浸式系统栏 Insets 和 Liquid Glass 状态卡片。
- 模拟器悬浮窗测试覆盖了在其他应用上层的玻璃样式、从 `(42,472)` 拖动到 `(308,720)`、位置恢复和干净移除（`OK (1 test)`）。
- Android 15 模拟器冷启动测试覆盖权限受限的初始界面、HID 注册、300 秒可发现请求、`connectedDevice` 前台服务和无崩溃停止服务。
- Lenovo TB322FC/Y700（Android 16，API 36）已与 Windows 主机配对为蓝牙 HID 键盘。Windows 捕获到了三个真实悬浮按钮发出的完整左方向键、空格键和右方向键按下/松开报告。
- 较早的真机测试还覆盖悬浮窗拖动、保存位置恢复、停止服务和自动重连。发布前仍应在真实蓝牙 Host 上复测调整后的“隐藏即断开”流程。
- Android 仪器测试会创建真实的 `TYPE_APPLICATION_OVERLAY`，检查三个按键映射、禁用及滑出触摸拦截、连接状态切换和悬浮窗移除。运行前需授予悬浮窗 AppOp（`OK (1 test)`）。

## 许可证

PauseSender 使用 [MIT License](LICENSE) 发布。第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
