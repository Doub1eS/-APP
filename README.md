# 练呗

原生 Android 日常记录应用。饮食、睡眠、训练、消费、今天状态、随手记与自定义类别统一按日期回看。

数据保存在本机 Room 数据库，不需要登录。首页提供 JSON 导出，卸载前请保存需要保留的记录。当前导出文件供备份与查看，尚无导入入口。

## 开发

Kotlin 2.1.20、Compose、Material 3、Room 2.7.1；最低 Android 8.0，编译 SDK 35，Gradle 8.11.1。JDK 17 或兼容的 JDK 21。

用 Android Studio 打开项目并设置 SDK，或者运行：

```powershell
./build-local.ps1 -Test
# 使用本机已有代理时按实际端口传入
./build-local.ps1 -Test -ProxyPort 7897
# 模拟器或测试手机连接后
./build-local.ps1 -DeviceTest -ProxyPort 7897
```

安装包构建目标：`app/build/outputs/apk/debug/app-debug.apk`。

## 当前验证范围

单元测试覆盖金额精度、非法金额、跨午夜和日间睡眠。设备测试覆盖六类录入、数据库重新打开、自定义类别、更新和删除。测试是否通过以实际构建报告为准；测试源码存在不代表已通过。

## 产品边界

第一版先完成低成本记录。年费摊算已移除。支付通知识别和 HyperOS 超级岛仍需后续适配，不包含在当前实现中。训练详细组数和重量目前可通过备注记录。
