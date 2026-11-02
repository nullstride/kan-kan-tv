# 固定前景颜色与玻璃可读性（2026-10-27）

用户反馈：亮背景上自动改为深色文字，会使密集列表出现黑白交错和视觉跳变。普通文字、提示与图标应保持稳定颜色；仅白色按钮和白色选中行使用对应状态的深色前景。

## 查阅的社区实现

- [Dimezis/BlurView README](https://github.com/Dimezis/BlurView#how-to-use) 提供 `blurOverlayColor`；[PreDrawBlurController](https://github.com/Dimezis/BlurView/blob/master/library/src/main/java/eightbitlab/com/blurview/PreDrawBlurController.java) 在模糊背景完成后调用 `canvas.drawColor(overlayColor)`，子控件保持正常绘制。
- [QmDeve/AndroidLiquidGlassView Config](https://github.com/QmDeve/AndroidLiquidGlassView/blob/master/core/src/main/java/com/qmdeve/liquidglass/Config.java) 提供 blurRadius、tintAlpha、tintColor 参数；其 shader 对背景执行染色混合。上游材质代码不要求按视频像素改写每段文字的颜色。
- 2026-10-27 读取上述远端源码；GitHub API 返回 403，改用 raw.githubusercontent.com 成功读取。此处描述可验证的实现能力，不宣称这些库对所有背景保证文字对比度。

## 本次实现

- 移除 GlassForeground 的亮度探测、每段文字配色、图标配色和滞回计时。保留仅设置文字阴影的辅助函数；视频画面不能修改 textColor、hintColor 或 drawableTint。
- 保留 control_text 的状态选择器：普通浅色，白色焦点/选中按钮深色；频道白色焦点行延续既有的深色文字。
- 参考 overlay/tint 方式，将固定的暗色衬底混入玻璃内层：控制条 0.56，侧栏 0.60。外缘保持原有较低染色，以 2–16dp 的平滑过渡保留折射、高光和曲面厚度。没有按单个文字生成额外色块。
- 保留 SurfaceView PixelCopy 与 TextureView 采样通道，因 BlurView 文档明确说明其普通 View 采样不能直接处理 SurfaceView，不新增库依赖替换播放器。

## 验证与范围

自动化覆盖背景从白/黑/黄切换时文字、提示和图标 ColorStateList 不变，白色控件选中时深色字、退出选中恢复浅色，及视频采样回调生命周期。真机检查使用 vivo PA2455 的白底、黑底、照片、棋盘、回退材质与两种视频渲染夹具。最亮背景上的细小次要文字仍可能需要进一步提升材质不透明度；不宣称全设备、真实 IPTV 网络均已验收。

当前工作区同时含播放器和设置整合。玻璃提交以合入前备份中的 MainActivity、ChannelAdapter 和 UI 回归测试作为暂存基线，加入本轮玻璃代码；整合后的磁盘文件原样保留为后续未暂存修改。提交前另导出暂存树运行独立构建/测试，以确认该提交不依赖整合中的未跟踪代码。

本轮结果：整合工作区 `tools\aio.cmd build` 通过，90 项测试零失败；玻璃专用暂存树导出后独立执行同一命令通过，22 项测试零失败。vivo 真机八组夹具完成，无 AGSL 初始化/绘制错误或应用崩溃。已目视复查白底、照片及密集频道列表，普通前景均为固定浅色，白色焦点行深色。证据和本轮整合版 APK：`../deliverables/liquid-glass-stable-text-20261027/`；该 APK 含当前未提交的播放器整合，不能当作玻璃专用提交的独立构建产物。
