# 第三方组件与许可

本项目在 anITer/utv 的 MIT 代码基础上开发。根 LICENSE 保留其版权；本项目新增的独立代码以 MIT 提供。下列组件和衍生文件保留各自许可，不受根 MIT 再许可覆盖。

| 组件 | 版本 / 范围 | 许可与来源 |
| --- | --- | --- |
| anITer/utv | 整合基线 8fcc355，应用与三网选择逻辑 | MIT；https://github.com/anITer/utv |
| bilibili/ijkplayer | k0.8.8，Java 播放接口与原生库 | LGPL-2.1-or-later；完整源码见 third_party/sources/ijkplayer-k0.8.8.tar.gz |
| Bilibili/FFmpeg | ff3.4--ijk0.8.7--20180103--001，含 MP2 注册补丁 | LGPL-2.1-or-later；源码、补丁与构建信息见 native/README.md |
| IRenderView、MeasureHelper、SurfaceRenderView、TextureRenderView、IjkVideoView | ijkplayer Android 示例的衍生实现 | Apache-2.0；Copyright 2015 Bilibili、Zhang Rui；文件头保留原始声明 |
| Mp2SoftwareDecoder | FFmpeg MPEG 音频表与算法的 Java 衍生实现 | LGPL-2.1-or-later；保留 FFmpeg/Fabrice Bellard 等原始权利，源码随本项目提供 |
| Android JNI header | android-14.0.0_r1 libnativehelper/include_jni/jni.h | Apache-2.0；Copyright 2006 Android Open Source Project |
| ExoPlayer | 2.19.1 core/extractor/HLS/DASH | Apache-2.0；https://github.com/google/ExoPlayer |
| AndroidX、Material Components | 版本见 app/build.gradle | Apache-2.0；https://source.android.com / https://github.com/material-components/material-components-android |
| AndroidLiquidGlassView 参考效果 | 许可副本见 assets/licenses | MIT；Copyright 2025–2026 Donny Yang |
| 预览风景图片 | docs/preview-image.md 中的原始图片 | Unsplash License；保留来源，未作为独立图片素材售卖 |

许可全文在 LICENSE、LICENSES/ 和 app/src/main/assets/licenses/。播放器共享库可自行修改、重建和替换；本项目不限制为调试 LGPL 修改而进行的逆向工程。

频道名称、运营商与组播地址用于识别和配置兼容网络，不表示本项目拥有电视节目内容。内置列表不提供订阅、账号、鉴权参数或节目内容授权。应在自己有权使用的 IPTV 网络中使用，需鉴权的频道应通过自有 M3U 导入。本项目的巡检脚本已采用独立 POSIX 实现；wuwentao/bj-unicom-iptv 仅作为历史协议/频道事实参考，不将其未明确授权的脚本作为本项目代码再分发。
