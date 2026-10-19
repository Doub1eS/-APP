# 0.7.0 后台采集进程与真机结论

更新：2026-10-20。本文件记录的是**已经实测到的结论**和**尚未验证的部分**，不把未测项写成已实现。

## 本轮改动（代码已实现）

- 采集与界面拆进程：`PaymentListener` 与 `PaymentWatchService` 运行在 `:capture` 进程，不加载 Room、不加载 Compose。
- 单写者存储：`:capture` 进程只把识别结果追加到 `files/capture-queue.jsonl`；默认（界面）进程是唯一的 Room 写入方，前台每 2 秒 drain 一次，通知按钮操作前也会先 drain。
- 跨进程状态：开关、会话、连接状态、诊断原因、去重身份、队列计数写在 `files/capture-state.properties`，用 `capture.lock`（OS 文件锁）保护读改写；不再依赖 SharedPreferences 与内存 StateFlow。
- 去重：`:capture` 进程用 `files/capture-seen.tsv` 记录身份哈希，先查后写；重复通知只补商户，不新增账目。重复通知里**新出现的身份**（例如后补的交易号）也会被记下，避免换 key 重发时二次入账。
- 常驻前台服务 `PaymentWatchService`（`specialUse` 类型、`START_STICKY`、`stopWithTask=false`），并在 `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` 时恢复；断线时 `requestRebind` 最多重试 4 次。
- 状态不再"猜测"：界面读状态文件，并用前台服务通知是否存在判断 `:capture` 进程是否真的活着（进程死了通知一定消失），避免把上次进程的残留值当成"正在监听"。
- 通知点击真正打开待确认页（补了 `onNewIntent`）；此前应用在前台时点通知不会打开。
- 诊断写入节流（同来源同原因 30 秒内不重复写盘）；隐藏内容正则从每次编译改为常量。

## 已实测结论（小米15 / HyperOS 3.0 / Android 16 / 24129PN74C）

### 1. HyperOS 的"从最近任务划走"等于强行停止整个应用

用户实际划走时抓到的系统日志：

```
ProcessSceneCleaner: SwipeUpClean: force-stop pkgName=com.lianbei.daily
ActivityManager: Force stopping com.lianbei.daily ... : SwipeUpClean
ActivityManager: Killing 20503:com.lianbei.daily:capture (adj 250): stop ... due to finished inst
NotificationListeners: 0 notification listener binding died: ComponentInfo{com.lianbei.daily/...PaymentListener}
```

结论：
- 划走时界面进程与 `:capture` 进程**都被杀**，前台服务、独立进程、`START_STICKY` 都不起作用；这不是内存压力回收，是 vendor 的显式 force-stop。
- 进程存在、服务绑定、系统"实时监听连接"三项在划走后同时为空。

### 2. force-stop 之后，通知监听无法由应用自己恢复

同一次划走后，在应用重新打开、进程与前台服务都恢复的情况下测试了三种应用侧恢复手段，全部无效：

| 手段 | 结果 |
| --- | --- |
| `NotificationListenerService.requestRebind()`（4 次 / 约 35 秒） | 返回成功，系统未重新绑定，`connected` 仍为 false |
| 重启进程 + 重新启动前台服务 | 进程与常驻通知恢复，监听仍未绑定 |
| 应用禁用/启用自身监听组件（`setComponentEnabledSetting`） | 系统读到组件变更，但没有重新绑定监听 |

唯一确认有效的方式是**变更系统的"通知使用权"授权集合**：`cmd notification disallow_listener` + `allow_listener`（等价于用户在设置里关闭再开启练呗）。重启手机同样会重新绑定。

因此"划走后继续监听"在 HyperOS 上无法用应用侧手段兜住；可行的产品做法只有三条：**降低发生概率（锁定任务/省电无限制/自启动）、如实显示断开、提供一次点击就能修复的入口**。

### 3. 拆分进程确实有效（除划走外的场景）

- 界面进程崩溃（`am crash`）后，系统**主动重启了 `:capture` 进程以重新绑定监听**：`Start proc ...:capture for service {com.lianbei.daily/.PaymentListener} caller=android`，监听自动恢复（`connected=true`）。
- 应用退到后台（Activity stopped）时，前台 drain 循环停止，付款通知仍被 `:capture` 进程接收、解析并写入队列；再次回到前台 2 秒内写入 Room。
- 常驻通知确实出现在系统通知里：`id=203 channel=payment_watch flags=ONGOING_EVENT|FOREGROUND_SERVICE`。
- 安装/升级后首次进入，监听未绑定时界面显示"已授权，监听尚未连接"并给出修复入口（不再伪装成监听中）。

### 4. 内存测量（同一构建、静置后）

| 进程 | TOTAL PSS | TOTAL RSS |
| --- | --- | --- |
| `com.lianbei.daily:capture`（仅监听+常驻服务，无 Room/Compose） | 约 62 MB | 约 148 MB |
| `com.lianbei.daily`（界面打开，含 Compose 与 Room） | 约 122 MB | 约 233 MB |

结论：后台常驻开销约为界面进程的一半，划走场景下如果只有 `:capture` 存活约 62 MB PSS。这只是单次快照，不能当作峰值或独占内存结论；事件前后增量、CPU 占用、锁屏 8 小时曲线尚未测。

## 尚未验证（不要在文档里写成已通过）

- 锁定最近任务卡片、省电策略=无限制、自启动允许之后，划走是否不再 force-stop（需要用户在真机上配合一次实验）。
- 锁屏 30 分钟以上、夜间长时间静置后，付款通知能否稳定收到。
- 支付宝真实付款通知、退款/转账/红包真实样本。
- 界面类设备测试（`DailyAppTest` / `ExpenseCategoryUiTest` / `RedesignTest`）在本机报 `Process crashed`，原因未定位，不能声称通过。
- `connectedAndroidTest` 在本机不可用：MIUI 拦截测试包安装，且 UTP 在安装失败时卸载了被测应用。

## 已跑的测试（本机真实设备）

- 单元测试 28 项通过（`:app:testDebugUnitTest`）。注意：工程放在中文路径下时 Gradle 测试 worker 会报 `ClassNotFoundException`，必须用 `F:\lianbei-build` 这个 ASCII 联接路径构建。
- 设备端 `PaymentCaptureTest` 6 项通过（捕获、去重、会话、商户补充、诊断不落原文、不提前写库）。
- 设备端 `PaymentsTest` / `FoodStorageTest` / `SubscriptionStorageTest` / `PaymentListenerSystemTest` 共 14 项，13 项通过；`PaymentListenerSystemTest` 首次失败是通知权限未授予，用户授权后单独重跑通过。

## 事故记录

执行 `connectedAndroidTest` 时 MIUI 拦截测试包安装，AGP/UTP 随后**卸载了被测应用**，导致本机数据被清空（已确认：本地只有 1 笔未确认的待确认记录，没有已确认的日常记录）。之后已重新安装、恢复通知使用权与通知权限、重新开启识别，并把应用加入电池优化白名单。测试一律改用 `adb install` + `am instrument` 直接执行，不再用 `connectedAndroidTest`。
