# 验证记录

日期：2026-11-16。源码、测试、技术设计与 APK 一并交付。

## 已完成

| 层次 | 方式 | 结果 |
| --- | --- | --- |
| Android 编译 | JDK 21、Gradle 8.14.1、AGP 8.9.2、SDK 35，`assembleDebug` | 成功生成调试 APK |
| Android 静态检查 | `lintDebug` | 0 错误；3 条建议见下文 |
| HTTP 协议 | `sh tests/server/run.sh`，实际 loopback Socket | 52 项检查通过 |
| 浏览器 | `node tests/web/smoke.mjs`，无头 Chrome，连续 multipart JPEG 测试帧 | 配对/错误、会话、上传字节一致、XSS 文件名、暂停恢复、断线恢复、退出均通过；没有 JS 异常 |
| 浏览器布局 | 1440 px 桌面、390 px 手机宽度截图 | 无横向溢出；已查看配对页、工作台和移动端截图 |
| Android 安装启动 | 已连接的 TWXJ_1P0，Android 14 | 安装成功、主界面显示正常、进入通知权限流程，无已观察到的启动崩溃 |
| Android 存储 / HTTP | 自带 StoreInstrumentation，在 Android 14 设备执行 | 15 项检查通过；不启动屏幕采集，不读取用户文件 |

设备测试使用 App 缓存下随机创建的隔离目录，完成后删除；测试包括文件名清理、2 MiB 字节一致性、元数据重载、截断传输清理、超限/零字节、16 个并发文件保存、移除持久化、崩溃残留清理，以及设备内 HTTP 静态资源、401、配对、上传和下载。此测试使用真实 Android 文件系统与网络栈，但网络回环不等同于跨设备 Wi-Fi 性能测试。

服务端测试使用测试 Backend；浏览器截图中的 “Android test frame” 为测试画面，不是真机投屏截图。Android 主界面截图只证明界面启动。

## 尚未完成的验证

用户选择暂不共享屏幕，本次没有接受 MediaProjection 系统授权。因此以下内容仍需实际设备验收：

- Android 桌面在另一台电脑浏览器中显示，屏幕内容更新后静止的最终一帧。
- 横竖屏、系统停止共享、锁屏、后台策略、停止后立即重新开始。
- 系统文件选择器导入和另存至真实文档提供器的完整交互。
- 同 Wi-Fi 与手机热点的端到端速度、持续运行、发热、耗电、实际帧率和延迟。
- Android 8–13、Android 15/16、其他厂商和其他浏览器的兼容性。
- 接近 1 GiB 的真实大文件及存储空间耗尽场景。

上述功能有实现代码，不能把构建或模拟后端测试视为已完成上述实测。

## 已知构建提示

`lintDebug` 保留 3 条非阻断建议：targetSdk 35 并非本机最高 API；AppSession 保存的是 applicationContext，静态字段规则仍提示需检查上下文泄漏；磁盘预检使用 getUsableSpace，没有主动清理其他应用缓存。它们不代表本轮已发现崩溃，也不构成更高系统版本的兼容性承诺。

Gradle 提示 Android 插件内存在未来 Gradle 9 不兼容的弃用行为。本工程固定 Gradle 8.14.1，请一起评估插件升级，不单独替换主版本。

## 复现命令

```sh
./gradlew assembleDebug lintDebug
sh tests/server/run.sh
node tests/web/smoke.mjs
./gradlew connectedDebugAndroidTest
```

首次 connectedDebugAndroidTest 可能下载 Android UTP 依赖。本次本地离线缓存缺少该依赖，因此采用同一测试 APK 的直接 instrumentation 方式，15 项检查实际通过：

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.airbridge.test/dev.airbridge.StoreInstrumentation
# 预期：OK (1 test, 15 checks)
adb uninstall dev.airbridge.test
```

调试 APK 已使用调试签名，适合本地试用；正式分发需要自行管理签名和开展上述真机验收。
