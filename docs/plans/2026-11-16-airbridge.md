# AirBridge Implementation Plan

**Goal:** Android 桌面通过同一 Wi-Fi 或热点投屏到浏览器，并双向传输文件。

**Architecture:** Android 原生 MediaProjection + 前台服务采集屏幕，JPEG 最新帧通过手机内置 HTTP/MJPEG 服务提供给浏览器。浏览器资源随 APK 离线打包。文件使用系统文件选择器导入/导出，流式上传下载，所有网络接口由每次启动生成的配对码保护。

**Tech Stack:** Java 17、Android SDK 35 / minSdk 26、AGP 8.9.2、原生 HTML/CSS/JavaScript，无运行时第三方库。

## 决策

- 已确认局域网/手机热点。无需云服务、账户、Root、ADB 或浏览器插件。
- MJPEG 相比 WebRTC 依赖少且可直接 HTTP 打开，代价是耗带宽、帧率较低、无音频。WebRTC 后续可优化为视频通话级别；H.264/WebCodecs 对浏览器与安全上下文要求更高。
- 手机明确点击开始并同意 Android 屏幕共享，前台通知可随时停止。停止会关闭所有浏览器会话。
- 手机分享文件复制到 App 私有收发箱；电脑上传也保存在此。手机可另存到用户选择的位置。文件不自动公开整个手机目录。
- 6 位随机配对码、HttpOnly/SameSite cookie、配对限流、请求与并发边界、文件名与路径隔离。
- 单文件最大 1 GiB；默认 1280 长边、12 FPS、JPEG 65，可在开始前选择清晰度。

## 实现与验收

1. 创建 Gradle 工程、manifest、权限与前台服务；执行 assembleDebug 和 lintDebug。
2. 实现单次授权采集和旋转处理；使用 Android 14+ resize 回调复用 VirtualDisplay。
3. 实现带身份校验的 HTTP 服务和最新帧 MJPEG；用 JVM socket 集成测试验证配对、未授权请求、上传下载、边界及停止。
4. 实现 Android 文件选择/持久化/另存与状态界面；检查权限拒绝和停止生命周期。
5. 实现响应式浏览器工作台，配对、预览、全屏、截图、上传进度与取消、下载列表、断线恢复。
6. 构建 APK，运行可用模拟器或真机检查；无法进行的设备验证单独记录。提供使用步骤、限制和排错说明。
