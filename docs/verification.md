# 首版验证记录

2026-10-14，本机 JDK 21、Android SDK 35、Gradle 8.11.1。

- `assembleDebug` 与 `assembleDebugAndroidTest` 成功，Kotlin 和 Room 生成代码编译通过。
- `testDebugUnitTest`：5 项通过，无失败或跳过。验证精确金额、非法金额、跨午夜睡眠、日间睡眠、非法时间。
- Android 15 Pixel 6 模拟器 `connectedDebugAndroidTest`：2 项通过，无失败。界面依次完成六类记录保存；断言类别齐全、金额 1234 分、睡眠 480 分、自定义类别 Reading；第二个数据库连接可读到全部六条记录。另验证更新替换、历史日期保留、删除。
- 单独安装生成 APK 成功，启动真实 MainActivity 返回 `Status: ok`，冷启动成功。
- 已查看真实首页截图：六个入口、日期导航、月消费与当日空状态可见，无内容截断。
- APK v2 签名验证通过。

交付文件：`releases/lianbei-0.1.0-debug.apk`。这是可安装的调试版本，不是商店发布签名版。最低 Android 8.0；实际设备验证目前覆盖 Android 15 模拟器，尚未在用户的小米真机上测试。

导出入口已实现 JSON 序列化与系统文件保存；系统文件选择器交互尚未自动化验证。支付通知识别、超级岛、图片和语音录入不在此版实现中。训练动作详细数据当前通过文字备注记录。
