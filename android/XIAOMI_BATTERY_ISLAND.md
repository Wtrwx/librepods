# AirPods 电量超级岛（HyperOS 3）

本功能沿用 LibrePods 的 libxposed API 101 入口，只在 `com.xiaomi.bluetooth` 主进程中运行。
在 LSPosed 中为 LibrePods 勾选 **小米蓝牙 / com.xiaomi.bluetooth**，安装更新后重启该进程或重启手机。
本功能本身不要求勾选 `com.android.bluetooth`；原有 LibrePods 蓝牙兼容功能可能仍需要它，因此模块原有推荐作用域保持不变。

## 行为

- 首次加载时读取已连接 A2DP 设备，随后监听 A2DP 连接及系统电量广播。
- 只匹配名称/别名含 AirPods 的设备，或 LibrePods 已保存 MAC 的设备（可支持改名耳机）。
- 每台设备连接后等待 1 秒，通过系统 `getBatteryLevel()` 获取总电量；未知时等待电量广播，最多接受连接后 15 秒内的数据。
- 同一次连接只显示一次；电量从 83% 变成 82% 不会重新弹出。每台设备另有 30 秒重连防抖。
- 大岛显示耳机名及 `83%`，小岛显示耳机图标及 `83`。0% 是有效值，未知电量不会假装成 100%。
- 岛在 5 秒后超时，通知在 5.5 秒后主动取消，同时设置 Android 系统通知超时。断开连接或关闭蓝牙会立即清除本功能通知。
- 通知归属小米蓝牙，独立静音渠道和设备专用 tag 避免覆盖小米自己的通知。
- 非 HyperOS 3 通知协议不启动；不修改系统超级岛权限或 SystemUI 白名单。小米蓝牙的通知及焦点通知权限必须开启。

## 协议依据

- [小米超级岛接入说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- [大岛及小岛设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143)
- 接入说明所链接的 2026-01-29 模板库：图文组件 1 用 `textInfo`；图文组件 6 用 `imageTextInfoRight.type=6`、`picInfo.type=4`、`textInfo.title`。
- 网页示例的 `miui.focus.paramtextInfo` 与 PDF 字段存在差异，大岛同时提供两个字段以兼容；图标通过 `miui.focus.pics` 中的 Android `Icon` 传入。

## 验证

```sh
cd android
./gradlew testFossDebugUnitTest assembleFossDebug compilePlayDebugKotlin lintFossDebug
adb logcat -s LibrePodsIsland LibrePodsHook
```

单元测试覆盖连接延迟、有效/未知电量、重复事件、断开取消、每设备防抖、两台设备并存、蓝牙关闭和 JSON 转义。
2026-09-05 验证：14 项测试通过，FOSS 调试 APK 构建及 Play Kotlin 编译通过。
Lint 仍报告原有 28 个错误；联网刷新依赖元数据后共 217 个警告，其中比先前多出的 28 个均为未修改依赖的版本更新提示，没有新增功能实现问题。
通知渲染和 LSPosed 注入仍须在支持超级岛的小米真机上验收：

1. 连接 AirPods：大岛显示名称/百分比，小岛显示图标/数字。
2. 5.5 秒后通知中心无残留；保持连接并更新电量，不再弹出。
3. 出岛前断开耳机或关闭蓝牙：不出现过期弹窗；出岛后断开：立即消失。
4. 间隔 30 秒以上重新连接：重新显示一次；普通非 AirPods 蓝牙设备不显示。
5. 系统始终没有有效电量时不弹岛，日志提示等待电量广播。

本功能读取系统总电量，不新增 AACP/蓝牙协议 Hook，也不推算左右耳或充电盒电量。
