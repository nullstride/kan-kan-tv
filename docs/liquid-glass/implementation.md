# 液态玻璃实现记录 · 2026-10-26

## 范围与基线

实际开发基线是 `6e1c865`，包含已经提交的设置分类重构。初始文档中的 `f73318f` 不是本次实际 HEAD。开始前保存了 117 个文件以及原 APK；不重置已有工作，不提交或推送。

备份：`../../../backups/20261026-liquid-glass/`。`manifest.json` 记录文件指纹；`baseline.apk` 是开始时 APK，`baseline-rebuilt.apk` 是当前基线源码重新构建的 APK。备份含本机构建配置，保持在本地，不作为公开交付内容。

本轮 MainActivity 只新增玻璃高光和按钮动画挂接；网络、播放器、配置持久化、频道库和扫描实现未修改。设置分类、全部原功能及已有图标均保留。

## 开源适配与许可证

采用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) 的 AGSL 光学代码，固定源码提交 `d4238c557e46271f29ec0e3d3ee211bcf58dc2e0`。

开发前下载核验 Maven `com.qmdeve.liquidglass:core:1.1.0`：AAR manifest 的 minSdk 为 24，aar-metadata 的 minCompileSdk 为 37，不能直接用于当前 minSdk21 / compileSdk34 工程。因此只移植 MIT 授权着色器，不引入整个 View 库和依赖，不提高工具链或系统门槛。

- 原始 MIT 头保留在 `app/src/main/res/raw/liquidglass_effect.agsl`。
- 完整许可证随 APK 打包：`app/src/main/assets/licenses/AndroidLiquidGlassView-MIT.txt`。
- 本地补丁：视频边界外返回黑色；五点空间滤波；轻微三通道色散取代七通道以控制开销；相邻帧时间混合；亮背景亮度压缩以保证文字可读。
- 未复制 Kyant Backdrop、Prismal 或 Cloudy 的代码；这些只参与方案调研。

## 实现结构

| 文件 | 职责 |
| --- | --- |
| VideoGlass | 共用一份背景采样、面板坐标映射、方向高光、前后台和可见性调度 |
| GlassLens | API33 隔离类；BitmapShader → RuntimeShader，只有背景经过折射 |
| GlassCaptureGate | 单请求所有权、切台代次、暂停/销毁时丢弃过期回调 |
| GlassMotion | 不消费触摸；190ms 焦点/按压/取消动画，尊重系统关闭动画 |
| GlassLabActivity（debug 源集） | 棋盘格、纯色、照片、SurfaceView/TextureView 测试片；没有桌面入口，不在 release 中 |

视频渲染方式保持原样：SurfaceView 用 PixelCopy，TextureView 用 getBitmap，示意图从 ImageView 取样。前景文字和图标由 Android 正常绘制，不进入 shader。共享采样长边 640px；静态图缓存；每次最多一个在途请求；已经交给 HWUI 的 bitmap 不主动 recycle。

Android 13 及以上使用 AGSL；旧系统或 shader 初始化/绘制异常使用可读毛玻璃。`glass_force_fallback` 是 debug Intent 开关，不进入用户设置。API21–32 真实设备验证仍待补充。

## 视频闪烁修复

第一轮 67ms 定时采样直接替换背景，用户实机反馈玻璃条闪烁。动态录像可见背景运动呈阶梯更新；日志未见着色器崩溃或反复切换后端。

当前改为 View 的屏幕帧回调调度，采样间隔下限 30ms（60Hz 设备约 30 次/秒），相邻采样帧进行 32ms 混合；发布新帧不清空旧画面。切台/尺寸变化仍显式清除，防止上一频道残留。纯白背景增加亮度压缩，避免白字融入背景。

这不是把整条控件做透明度动画：按钮/文字不参与帧混合。控制条隐藏时停止捕获和帧动画，只每 250ms 检查是否重新显示；Activity 暂停时移除所有调度。

修复前后移动网格录屏的玻璃中央 ROI 帧间像素差 p95 从 7.63 降至 2.20（0–255 色值单位）。录屏帧率不同且包含启动/循环边界，因此该数字只作跳变减小的辅助证据，不能作为直播“完全无闪烁”的证明。

## 执行入口

Windows 从直接启动的 CMD 运行：

```bat
tools\aio.cmd build
tools\aio.cmd install <device-serial>
```

构建入口包含本机 JDK 临时目录修复，执行 `testDebugUnitTest assembleDebug`；安装使用 `--no-streaming -r`，不清数据。

测试场景必须重建 Activity，避免 `am start` 只把新参数投递给旧实例：

```text
adb -s <serial> shell am start -S -n com.bjunicom.iptv/.ui.GlassLabActivity --es mode checker
adb -s <serial> shell am start -S -n com.bjunicom.iptv/.ui.GlassLabActivity --es mode white
adb -s <serial> shell am start -S -n com.bjunicom.iptv/.ui.GlassLabActivity --es mode surface
adb -s <serial> shell am start -S -n com.bjunicom.iptv/.ui.GlassLabActivity --es mode texture --ez hide_panels true
adb -s <serial> shell am start -S -n com.bjunicom.iptv/.ui.GlassLabActivity --es mode checker --ez glass_force_fallback true
```

视频读取应用 external-files 下的 `glass-test.mp4`。测试用自生成 MPEG-4 网格和 [Sintel 预告片](https://media.w3.org/2010/05/sintel/trailer.mp4)，均不纳入生产频道和 APK。`tools/glass_video_check.py generate <path>` 生成运动网格，`inspect <recording>` 导出逐帧联系表和 ROI 数据，需要本机 Python/OpenCV/Pillow/NumPy。

## 尚待完成

当前是预览实现。没有完成三轮标准 A/B、30分钟稳定性、三内核全部真实播放、4K/HEVC、旧系统与电视硬件验收。TextureView 日志抽样出现 6ms 捕获，超过初始 4ms 目标，不能声称性能预算全部达标。没有修改网络连通性，不把测试片当作 IPTV 恢复证据。
