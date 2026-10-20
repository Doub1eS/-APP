# 后台采集可靠性 Implementation Plan

**Goal:** 接续488b9f9，保留独立采集进程，先解决丢账、并发导入、状态误报，再验证UI回归。
**Architecture:** 文件锁串行化授权会话检查、队列落盘和去重索引；队列先于去重提交，Room幂等恢复。主进程互斥导入，后台状态以实际进程/服务连接证据核实。
**Tech Stack:** Kotlin、Compose、Room、Android NotificationListenerService、AtomicFile。
**Spec:** docs/next-steps.md；用户授权自主检查并继续，无手机无线调试。

- [x] 先写故障回归：写队列失败后重试、身份补全持久化、导入期间新增条目计数、并发导入、取消后恢复。
- [x] CaptureStore/PaymentCapture：在同一锁中校验会话并先保存完整事件，后提交身份；重复事件也持久化新身份供Room去重；文件写入错误不静默吞掉，取消传播。
- [x] CaptureDrain：互斥，读取失败不清空，失败/取消可重放，保留新队列计数。
- [x] 监听状态与生命周期：不以通知可见性或历史布尔值判断活跃；页面停止时暂停轮询；常驻通知不能在断开时声称监听正常；修正划走必保活文案。
- [x] 调试Receiver仅允许具有系统DUMP权限的调用方；移除采集进程写Room的调试路径。
- [x] 本地构建、单元测试、模拟器采集与UI测试，复核既有Process crashed（冷启动模拟器上UI组通过；旧环境根因未确认）；不连接或安装到用户手机。
- [x] 更新0.7.1验证、README与下一步；真机功耗/锁屏/HyperOS清理待重新授权连接后验证。

追加完成：旧偏好迁移保留AtomicFile备份；常驻服务快速开关崩溃修复与系统回归，关闭常驻设置在重启/升级后保留。验证细节见 docs/capture-v7.1-verification.md。
