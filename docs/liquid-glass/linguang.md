# 鳞光玻璃

2026-11-04 确认的侧栏内层材质。它延续原来的九点采样柔化与叠影，在弱细节区域用同一种采样核补充周围内容，不额外生成纹理。

## 默认参数

| 参数 | 数值 |
| --- | --- |
| 内部基础采样半径 | 6 dp |
| 弱区域采样上限 | 20 dp |
| 弱区域补充强度 | 70% |
| 亮部压缩 | 关闭，独立于采样 |
| 边缘基础采样半径 | 0.75 dp |

`GlassLens` 按设备 density 将半径换成物理像素。`liquidglass_effect.agsl` 保留原折射、色散、光照及九点权重（中心 0.25、四正向各 0.125、四角各 0.0625）。

内部以九点亮度标准差判断细节：0.014–0.045 平滑过渡到原有采样；较弱处按 10、20、40、80 dp 搜索，受 20 dp 上限和视频源边界约束，以 0.025–0.065 标准差平滑选择尺度。达到上限后合并重复尺度的权重，避免重复读取相同采样点。保留现有相邻视频帧混合。

## 生效范围

- Android 13 及以上的频道侧栏，以及通过 `bindDialogGlass` 注册的 Regular 弹窗，自动使用此材质。
- Clear 控制条继续使用轻微散射；光学边缘不参与自适应补充。
- Android 12 及以下或 AGSL 不可用时，继续使用原有降级材质。
- 文字、图标由 Android 正常绘制，不进入背景采样。

静态 HTML 中的 1 CSS px 对应实验的 1 dp；真机结果仍受屏幕密度、面板尺寸、视频捕获分辨率和背景内容影响。均匀纯色不能凭采样生成细密纹理，不承诺所有节目画面都呈现相同强度。

## 验证入口

```bat
tools\aio.cmd build --isolated-network
```

debug 夹具增加 `regular_glass` 参数，用于验证实际侧栏 shader 分支：

```text
adb -s <device-serial> shell am start -S -n com.kankan.iptv/.ui.GlassLabActivity --es mode photo --ez regular_glass true
```

纯色、棋盘和视频可以将 `mode` 改为 `white`、`black`、`checker`、`surface` 或 `texture`。视频需要先准备既有的本地 `glass-test.mp4` 夹具。截图、日志、测试片和 APK 均保留在仓库外。

准备测试片后，可用既有脚本检查 Regular 材质的八组场景：

```text
node tools/glass-device-check.js <device-serial> <output-directory> --regular
```

## 本轮验证

- `tools\aio.cmd build --isolated-network` 成功；153 项单元测试通过，零失败、零跳过。
- vivo PA2455 / Android 14 安装并运行 debug 包。Regular 照片、纯白、纯黑、棋盘、强制降级、SurfaceView、TextureView、面板隐藏八组夹具完成，日志未出现 AGSL 初始化/绘制错误或 Java 崩溃。
- 实际主界面频道侧栏已截图确认，日志为 `backend=AGSL optics=linguang-6dp`。
- 本地视频短测中，SurfaceView 帧时间 p95 为 15 ms，TextureView 为 36 ms；TextureView 存在掉帧，未达到完整性能验收。夹具同时包含捕获、播放、录屏和前后台切换开销，不能将这些数字归因为着色器本身。
- 本轮未验证真实 IPTV 的长时间稳定性、旧系统真机或电视设备。没有将 HTML 与原生 Android 宣称为像素完全一致。
