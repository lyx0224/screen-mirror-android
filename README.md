# 轻投 AirBridge

Android 设备通过同一 Wi-Fi / 手机热点，把桌面投屏到浏览器，并双向传文件。Android 原生 Java 工程，浏览器页面内置于 APK；无云服务、无账户、无运行时第三方依赖。

## 使用

1. 在 Android 8.0 及以上设备安装 `AirBridge-debug.apk`。
2. 手机与电脑连接同一 Wi-Fi，或让电脑连接手机热点。
3. 手机打开「轻投」，选择画质，点击「开始投屏」，同意系统屏幕共享。
4. 电脑浏览器打开手机显示的 `http://局域网IP:8080`，输入本次 6 位配对码。
5. 手机选择文件后，电脑可在网页列表下载；电脑拖文件到网页上传后，手机在收发箱点击「另存到手机」并选择保存位置。
6. 手机点击「停止投屏与连接」或通知栏「停止共享」，关闭服务和所有浏览器连接。

推荐先用桌面 Chrome / Edge。已自动验证 Chrome；Safari、Firefox 和不同厂商 Android 系统仍需实测。浏览器无法访问时检查地址是否是 **http**、是否同一网络、路由器是否开启访客隔离/AP 隔离、是否有 VPN 或防火墙拦截 8080。多个地址时，选择与电脑同网段的地址。

## 功能与范围

- 桌面画面、横竖屏适配；浏览器暂停观看、全屏、截图、断线后重新连接。
- 三档画质：长边 960 / 1280 / 1920 像素，目标 8 / 12 / 15 FPS；实际帧率取决于屏幕更新、设备性能与网络。
- 双向文件传输，单文件最大 **1 GiB**；最多 200 个收发箱文件，最多 4 个浏览器同时观看。
- 每次启动生成配对码；已配对浏览器可看屏幕与下载整个 App 收发箱。停止会使会话失效。
- 文件保存于 App 私有收发箱，重启后仍在；另存到用户所选目录后可长期保留。移除只删除 App 副本；卸载会清空 App 收发箱。
- 当前仅画面，**不含音频、远程触控、公网投屏、断点续传**。MJPEG 更适合演示和工具使用，不用于低延迟游戏。
- 屏幕共享必须由用户在 Android 系统弹窗同意。安全窗口/DRM 内容可能黑屏；系统锁屏、终止共享或杀进程会中断。
- 局域网 HTTP **未加密**。配对控制访问权限，不提供链路加密；只在可信网络使用，不要把端口映射到公网。

## 源码构建

需要 JDK 17 或 21、Android SDK Platform 35 / Build Tools 35.0.0。使用 Android Studio 打开工程并安装缺失 SDK；首次同步需要下载 Gradle 和 Android 构建插件。

```sh
# local.properties 由 Android Studio 自动生成；也可设置 Android SDK 路径
# sdk.dir=/your/path/to/Android/sdk
./gradlew assembleDebug lintDebug
```

Windows 使用 `gradlew.bat assembleDebug lintDebug`。输出：`app/build/outputs/apk/debug/app-debug.apk`。交付 APK 使用调试签名；正式发布应自行配置 release 签名，避免将密钥提交到源码。

## 目录

```text
app/src/main/java/dev/airbridge/
  MainActivity.java       手机界面、系统授权与文件选择
  ProjectionService.java 前台服务与启停
  ScreenCapture.java     屏幕采集、缩放、JPEG 与旋转
  AppSession.java        会话、设备状态和网络接口适配
  TransferStore.java     文件持久化、导入、另存与清理
  server/                HTTP、配对、MJPEG 和帧缓冲
app/src/main/assets/web/ 浏览器 HTML / CSS / JavaScript
app/src/androidTest/     无需屏幕共享的设备文件存储测试
tests/server/           真实 socket 的 JVM 协议集成测试
tests/web/              Chrome 浏览器集成测试（测试数据）
docs/TECHNICAL_DESIGN.md  实现与技术原理、接口、设计取舍
docs/VALIDATION.md       验证结果与待测项目
scripts/package-source.py 生成不含本机配置/构建缓存的源码 ZIP
```

## 验证

```sh
# 无外部依赖的 Java 协议测试，需要 JDK
sh tests/server/run.sh

# 浏览器测试，需要 Node.js 22+ 和 Chrome/Chromium
# 可使用 CHROME_BIN 指定浏览器可执行文件
node tests/web/smoke.mjs

# 无需屏幕共享的 Android 文件存储测试，需连接测试设备
./gradlew connectedDebugAndroidTest
```

完整原理见 [技术文档](docs/TECHNICAL_DESIGN.md)，实际验证边界见 [验证记录](docs/VALIDATION.md)。
