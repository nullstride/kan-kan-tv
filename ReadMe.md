# KanKan TV（看看电视）— 北京 IPTV 播放器

面向北京 IPTV 的 Android / Android TV 直播播放器，支持北京联通、北京移动、北京电信三网选择、三网聚合与自定义 M3U。适配电视遥控器、手机和平板，支持高清/4K、快速切台、频道收藏、网络检测和组播扫描。

## 节目源与网络

- 联通、电信组播需要对应运营商的 IPTV 网络；家庭 Wi-Fi 可通过自己配置的 udpxy 或正确的组播转发使用。
- 移动列表保留频道与基础地址，开源版本已移除上游账号及鉴权查询参数。部分频道可能需要你导入自有授权 M3U，内置频道数量不代表实际可播放数量。
- 自定义 M3U 只在本地导入；本项目不提供订阅账号或电视节目内容授权。
- 首次打开选择节目源；播放器菜单中的“网络检测”用于确认当前网络是否可用。

## 1.3.4 更新

- 整合上游 1.3.1（89640d0）的联通时移、电信频道表与扫描端口更新。
- 节目源检测最多抽查 4 个不同频道，代理或组播失败后补测另一种连接方式，减少漏判。
- 首次使用默认 UTV（ExoPlayer）内核，已保存的内核选择保持有效。
- 时移需要频道和 IPTV 网络支持：电视全屏长按 OK 2 秒，手机从“更多 → 时移回看”进入；点击直播时间返回直播。
- 设备与频道观看上报继续关闭。

## 功能

- UTV/ExoPlayer、Ijk 与系统播放器；硬件解码、MP2 软件伴音和错误回退。
- RTP/UDP 直连、路由器 udpxy、本地组播代理；频道搜索、分组和收藏。
- 手机/平板触控与 Android TV 遥控器，深色玻璃界面及横屏适配。
- 组播扫描、自定义播放列表导入导出、独立 POSIX 巡检脚本。
- 设备上报及上游自动更新关闭；许可在设置的“频道管理 → 开源许可”中离线查看。

## 构建

需要 JDK 17、Android SDK 34。Debug 使用 Android 默认调试签名，Release 保持未签名；请自行在本地签名，勿提交密钥。

```text
# Windows 原生 CMD
tools\aio.cmd build --isolated-network

# Linux/macOS
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

APK 位于常规的 app/build/outputs/apk/；Windows 隔离验证构建位于 build/network-validation/app/outputs/apk/。

应用包名为 `com.kankan.iptv`。包名变更后会作为独立应用安装，原应用的设置与收藏不会自动迁移。

```text
node --test tools/privacy-check.test.js
node tools/privacy-check.js history HEAD
node tools/public-release-check.js
node tools/native-libraries.js verify
```

巡检脚本示例：`sh check_iptv_m3u.sh -f own-playlist.m3u --no-screenshot`。完整参数用 `--help` 查看；请只检测自己有权访问的网络。

## 开源与第三方代码

应用基于 [anITer/utv](https://github.com/anITer/utv) 开发，根 [MIT 许可证](LICENSE) 保留上游署名。FFmpeg/ijkplayer 与 MP2 衍生部分采用 LGPL-2.1-or-later；部分渲染示例采用 Apache-2.0。组件范围和版权见 [第三方声明](THIRD_PARTY_NOTICES.md)，不要把第三方部分一概视为 MIT。

本仓库同时提供原生库对应的完整源码归档、源码补丁、精确二进制校验与构建入口，见 [原生库说明](native/README.md)。发布 APK 时请同时提供这些源码和许可材料。

感谢 bilibili/ijkplayer、FFmpeg、ExoPlayer、anITer/utv、zzzz0317/beijing-unicom-iptv-playlist、AndroidLiquidGlassView，以及 wuwentao/bj-unicom-iptv 对 IPTV 频道事实与网络知识的整理。频道事实参考不构成对节目内容的授权；巡检脚本采用本项目独立实现。

[贡献说明](CONTRIBUTING.md) · [使用隐私](PRIVACY.md) · [安全反馈](SECURITY.md) · [播放器架构](docs/player-architecture.md)
