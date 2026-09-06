# AirPods 电量超级岛（HyperOS 3）

本功能由 LibrePods 自己的 `AirPodsService` 发布通知，不再借用 `com.xiaomi.bluetooth` 的进程和通知身份。
超级岛通知本身不需要在 LSPosed 中勾选 **小米蓝牙 / com.xiaomi.bluetooth**；LibrePods 原有的蓝牙兼容 Hook 是否需要该作用域，仍按模块原有配置决定。

## 行为

- LibrePods 的 AACP socket 连接成功后开启一次显示会话，并直接使用 AirPods 协议返回的左右耳电量。
- 左右耳均有效时显示较低值；只有一侧在线时显示在线侧电量。未知电量不会触发通知。
- 连接后等待至少 1 秒再显示，最多接受连接后 15 秒内的数据。
- 同一次连接只显示一次；电量从 83% 变成 82% 不会重新弹出。每台设备另有 30 秒重连防抖。
- 大岛只显示耳机图标及 `83%`，小岛显示耳机图标和电量圆环；耳机名仅用于无障碍描述。0% 是有效值，未知电量不会假装成 100%。
- 大小岛共用的耳机图标按原始宽高比等比缩放，居中绘制在透明正方形画布内，避免纵向拉伸。
- 连接事件由后台服务直接触发入岛，不需要先打开 LibrePods 再上滑退出。不显示通知中心焦点卡片，也不先显示展开的悬浮卡片。
- 岛在 5 秒后超时，通知在 5.5 秒后主动取消，同时设置 Android 系统通知超时。断开连接或关闭蓝牙会立即清除本功能通知。
- 通知归属 LibrePods，使用独立静音渠道和设备专用 tag。
- 非 HyperOS 3 通知协议不发布；不修改系统超级岛权限或 SystemUI 白名单。LibrePods 的通知与超级岛（系统仍称为焦点通知）权限需开启；隐藏焦点卡片通过通知参数控制。

## 后台直接入岛的参数

2026-09-06 通过 ADB 从真机提取并用 JADX 1.5.3 核对：

- 系统：`OS3.0.304.0.WPCCNXM` / Android 16，`notification_focus_protocol=3`。
- `com.android.systemui`：`16.03.251211.r`。
- `miui.systemui.plugin`：`17.1.3.76.2` / `171037602`。

插件的 `FocusNotificationController.addDynamicIslandView()` 将 `islandFirstFloat` 作为展开标志传给 `DynamicIslandWindowViewController`。`AddEventCoordinator.handleAppEvent()` 检查 `canExpanded()`：只要此标志为真且有展开视图，就会优先显示展开卡片。原实现提供了 `baseInfo` 并开启 `islandFirstFloat`，后台发布后先显示卡片，默认约 5 秒才收起；通知自身 5.5 秒就取消。应用在前台上滑退出走另一条应用收起路径，因此出现了“只有上滑退出才看到岛”的现象。

现在使用：

- `islandFirstFloat=false`：首次直接进入电量岛状态。
- `enableFloat=false`：更新也不展开卡片。
- `isShowNotification=false`：插件拦截通知中心卡片展示，仍独立处理超级岛。
- 不提供 `baseInfo`：`TemplateFactoryV3.createStandardTemplateView()` 跳过焦点/展开视图构建，`param_island` 仍由岛模板正常解析。
- 保留 `mFocusNotification=true` 和 `miui.focus.param`：它们是超级岛协议标识，删除会连同超级岛一起失效。

没有添加 Activity 拉起、模拟上滑、系统广播伪装或新的 SystemUI Hook。

## 协议依据

- [小米超级岛接入说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)
- [大岛及小岛设计规范](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143)
- [HyperIsland](https://github.com/1812z/HyperIsland) 及其使用的 `hyperisland_kit 0.4.4`：HyperOS 3 使用 `protocol=3`，大岛左右分别使用 `imageTextInfoLeft` / `imageTextInfoRight`，小岛使用 `combinePicInfo`。
- [HyperLyric](https://github.com/limczhh/HyperLyric) 的无 Root 焦点通知路径：高重要性静音渠道、`mFocusNotification=true`、公开可见性和高优先级通知。
- [课程表超级岛](https://github.com/Mercury000/xiaoaiisland) 的可用模板也使用 `imageTextInfoLeft` 与大岛右侧独立文本组件。
- 图标通过 `miui.focus.pics` 中的 Android `Icon` 传入；小岛外圈用真实电量绘制圆形进度。

## 验证

```sh
cd android
./gradlew testFossDebugUnitTest assembleFossDebug compilePlayDebugKotlin lintFossDebug
adb logcat -s LibrePodsIsland AirPodsService
```

单元测试覆盖连接延迟、有效/未知电量、重复事件、断开取消、每设备防抖、两台设备并存、蓝牙关闭、JSON 转义，以及隐藏焦点卡片/关闭展开标志/不生成 `baseInfo` 的回归检查。
2026-09-06 验证：17 项测试通过，FOSS 调试 APK 构建及 Play Kotlin 编译通过；修复 APK 已通过 ADB 覆盖安装到上述真机。
同日通过 ADB/JDWP 在真实 `AirPodsService` 上调用生产代码的 `connected()` / `battery()`，输入测试电量 83%，全程未启动 LibrePods Activity。SystemUI 日志确认授权和解析成功、`expand:false`、`canExpanded false`、`userSwipe:false`，状态直接从 `Init` 进入 `BigIsland`，随后记录超时删除及通知移除。测试会话和调试转发均已清理。此项验证使用模拟连接/电量回调，且当时手机安全锁屏，不能替代用户亮屏后真实耳机重连的观感验收。
2026-09-05 的 Lint 基线有 28 个原有错误，本次不涉及这些错误对应的代码。
真机验收步骤：

1. 保持 LibrePods 在后台，收起通知栏/控制中心后连接 AirPods：直接显示耳机图标和百分比的电量岛，不先弹焦点卡片。
2. 展开通知中心时无本功能焦点卡片；5.5 秒后通知完全清除；保持连接并更新电量，不再弹出。
3. 出岛前断开耳机或关闭蓝牙：不出现过期弹窗；出岛后断开：立即消失。
4. 间隔 30 秒以上重新连接：重新显示一次。
5. AirPods 协议没有返回有效电量时不弹岛。

本功能读取 LibrePods 已解析的 AirPods AACP 电量，不新增蓝牙协议 Hook，也不使用小米蓝牙的系统总电量。
