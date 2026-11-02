# 上游源码整合与验证（2026-10-27）

> 后续已按用户确认方案补齐可见入口与设置保存流程；本文保留第一轮整合时的验证基线。当前交互与最新验证见 [节目源设置整合交付记录](source-settings-validation-20261027.md)。

## 范围与基线

- 上游：[anITer/utv](https://github.com/anITer/utv)，1.3.0，提交 8fcc35502cacd21c65705c64c18ba122dbecf83f。
- 共同基线：34114087b6220f8664e690a944fa799474998e68；整合前本地 HEAD：c17bed20248b0596ebdb55dc6348e24ea555f36f。
- 本次是工作区的源码整合；没有移动 HEAD、创建分支、创建合并提交或推送。后续保存时需要连同原有未提交的定制代码一起审查。Git 的 ahead/behind 仍反映提交历史，不能用来判断这次源码是否已整合。
- 完整文件备份：<workspace>\backups\20261027-before-upstream-merge-1793087158659。其中 merge-baseline.json 保存了 149 个整合前文件的 SHA-256；手机与 Pad 的原安装包也保存在该目录。

## 合入内容

- UTVPlayer / ExoPlayer 单前台播放器、邻台 TS 预收、MP2 软解和硬件失败回退。
- Ijk 组播起播参数验证与扩大探测窗口；旧频道回调隔离、网络与解码错误分类、外部代理失败缓存。
- 多网卡组播接收与 RTP/TS 校验、联通/移动/电信/自定义节目源、M3U 解析与扫描结果应用。
- 移植上游播放器与解析测试；保留本地玻璃测试、启动与恢复回归，并新增 7 项功能整合测试。

## 保留的本地行为

- 原有布局、主题、颜色、图片、图标、着色器、玻璃实现及焦点动画；68 个已有资源和玻璃源码文件逐一核对 SHA-256，内容没有变化。
- 原三页设置、频道抽屉、搜索、仅看可用、收藏、数字选台、遥控器与触屏入口。
- 起播/缓冲超时、失败后重连、旧回调隔离、本地播放诊断、预览图与失败恢复控件。
- 原扫描导出仍生成 modified_iptv.m3u 和 results.csv；扫描完成后新增「立即应用/保存到本地」选项。长按原导出按钮也可打开该选项。
- 新节目源和自定义 M3U 通过已有快捷菜单（菜单键/长按 OK，或长按设置按钮）进入，不在现有主界面或设置页增加按钮。
- 原 IjkExoPlayer 选项连接到上游的新 UTVPlayer；保留原控件文字和布局。硬件选项同时适用于 Ijk 与 UTV，OpenSL ES 仅适用于 Ijk。
- 上游自动更新模块已合入，但 ENABLE_UPSTREAM_UPDATES=false，避免原版 APK 覆盖定制界面；设备上报模块 ENABLE_DEVICE_REPORTING=false，不在启动时联网采集或上报。

## 验证

- 原有 Windows 入口 tools/aio.cmd build：testDebugUnitTest 与 assembleDebug 通过。
- 79 项测试，0 失败、0 错误、0 跳过。含 7 项整合测试：三网切换、自定义 M3U、扫描结果保存/应用、服务默认关闭、触屏新功能入口、Exo 选项和 Surface 保留、M3U+CSV 双格式导出。
- git diff --check 通过；原有未提交的 UI 变更仍保留，不能把相对 HEAD 的旧改动误认为本次重画 UI。
- Pad：vivo PA2455，Android 14 / API 34，2800×1968 横屏。确认实际安装 versionCode=130、versionName=1.3.0。
- Pad 的网络连接、播放设置、频道管理三页均对比旧安装版本的控件 ID、文字、位置、勾选和启用状态，一致；截图保存到交付目录的 pad 子目录。
- Pad 玻璃回归脚本退出 0：white、black、photo、checker、fallback-white、surface、texture、hidden 共 8 个场景全部通过。SurfaceView 与 TextureView 日志均确认 AGSL 后端及 video-start；截图已核对设置页与玻璃外观。证据见 pad/glass-device-check.log 和 pad/glass-check。该结果仅覆盖本地夹具渲染与播放启动，不代表动态视频性能完整验收。
- 真实 IPTV 网络、目标盒子上的 4K/MP2 原生硬件解码及切台性能尚未验收。玻璃夹具使用设备上的本地测试视频，不等于运营商直播测试。
- OPPO：启动和网络设置页核对通过；录屏脚本未完成，不能认定该设备的视频玻璃回归通过。用户切换 Pad 后，正式设备验证以 Pad 为准。

## 交付

- APK：<workspace>\deliverables\upstream-merge-20261027\看看电视-1.3.0-保留UI-合并验证版.apk。
- 证据：<workspace>\deliverables\upstream-merge-20261027；构建日志、测试汇总、Pad 前后截图和玻璃回归日志/录像。
- 机器可读记录：upstream-integration-20261027.json。
