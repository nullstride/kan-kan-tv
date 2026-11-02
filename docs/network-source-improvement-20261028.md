# 网络与节目源优化交付（2026-10-28）

## 交付结果

- 定制版升级为 1.3.1，versionCode 131；包名仍为 `com.bjunicom.iptv`。
- APK：`<workspace>/deliverables/network-improvement-20261028/看看电视-1.3.1-网络与节目源优化.apk`。
- SHA-256：`55ac77cf5b590e0fc6fdbc6034c33b5ff94da4f74f8e0796a3d0f7c1273b2c33`。
- 保留工作区已有的手机设置界面修改，接入对应的手机／平板／电视网络检查入口。未提交、未推送。

## 用户可见变化

1. 没有明确保存过节目源的安装，现在先显示选择引导，选择北京联通、移动、电信，或其他地区／手机流量／自定义源。未选择时不自动播放继承的联通默认频道。
2. 引导明确说明内置源适用于北京对应运营商，SIM 卡不能代表 Wi-Fi 背后的宽带运营商。其他地区通过现有 M3U 导入流程配置；取消或稍后设置不会偷偷确认默认源。
3. 已有明确选择的节目源继续保留；网络变化不会擅自覆盖用户选择。网络断开、链路变化或跨后台返回时比较实际网络的本地指纹，并提示重新检测。
4. 在“设置 → 网络连接”增加“检测当前网络与可用节目源”。检测使用已保存配置，每个来源抽查至多两个代表频道，包括当前源与三家内置源，不修改原列表、可用标志或节目源配置。
5. 结果显示各来源收包数量与失败原因。当前源收到媒体就建议继续使用，否则建议抽查收到媒体的其他源；只有用户进入节目源设置并保存后才会切换。
6. 检测可取消，离开页面、关闭应用或网络改变时终止检测；旧网络的异步结果不会继续显示。结果只代表抽查和媒体可读取，不承诺全列表或设备解码成功。
7. 播放失败提示区分网络、媒体格式和音视频解码错误。HTTP/HLS 失败不再要求用户配置组播代理；解码失败引导更换内核或关闭硬解。

## HLS 与 HTTP 检查

- 原来只读到 `#EXTM3U` 就判断成功；现在跟随受限次数的 HTTP 重定向和主／子播放列表，按最终地址解析相对路径，并实际读取媒体分片。
- TS 必须包含多个对齐的同步包；分片 MP4 检查初始化内容及 moof/mdat 媒体内容；HTTP 200 登录页、空列表和缺失分片不算成功。
- 检查 AES-128 密钥是否可读取，并支持显式及连续字节范围。其他受保护格式明确提示需要实际播放器确认，不把播放列表可访问当作播放成功。
- 错误原因包括 DNS、超时、HTTP 401/403 授权／地区限制和其他 HTTP 状态；限制请求时间、列表大小、递归层数、重定向次数及媒体读取量。
- HTTP 媒体检查不再根据频道名字伪造“实测”编码、分辨率和音轨；这些信息留待实际播放确认。
- 未引入 SIM 识别、IP 归属地服务或额外权限；未开启原版设备上报及自动更新。

## 主要实现

- `config/IptvSettings.java`：明确保存过的节目源判定、本地网络指纹。
- `network/NetworkSnapshot.java`：观察实际连接，兼容没有默认互联网路由的 IPTV 有线网络。
- `network/HttpStreamProbe.java`：可取消、有读取上限的 HTTP/TS/HLS 检查。
- `scanner/MulticastScannerEngine.java`：频道表巡检、M3U 检测和快速抽查共用改进后的 HTTP/HLS 检查。
- `ui/SourceNetworkController.java`：首次引导、网络变化提醒、抽查与建议、取消及过期结果保护。
- `ui/MainActivity.java`、`ui/SourceSettingsController.java`：生命周期、设置保存及失败提示的接入。

## 验证

- `tools/aio.cmd build --isolated-network` 成功，执行 `testDebugUnitTest` 和 `assembleDebug`。
- 21 个套件、123 项测试，0 失败、0 错误、0 跳过。本次新增 22 项网络与首次使用相关测试，并保留已有界面工作及其测试。
- 本地 HTTP 服务测试覆盖：播放列表成功但分片 404、200 错误页、空列表、TS 对齐、重定向后相对地址、主／子列表、403、密钥拒绝、字节范围、循环列表、分片 MP4、取消及超时。测试不依赖运营商服务或公网。
- 界面回归覆盖首次未选择不能默认播放、移动选择与保存、已有用户保持选择、断网不覆盖节目源、手机和平板检查入口、默认源显式保存、采样不修改原数据、HLS 和解码错误提示。
- `git diff --check` 与新增设备脚本语法检查通过。
- 测试结果：`build/network-validation/app/test-results/testDebugUnitTest/`；交付目录包含构建日志、`validation-summary.json` 和 `SHA256.txt`。

## 实机边界

- 尝试在连接的 vivo PA2455 上安装独立包 `com.bjunicom.iptv.networkcheck`，系统返回 `INSTALL_FAILED_ABORTED: User rejected permissions`。未继续绕过或重复安装；测试包未安装成功，原应用未被覆盖。
- 因此本次没有完成实机界面验收，也没有完成真实移动网络、4K 或设备解码验收。自动测试和 APK 构建成功不能替代这些结果。
- `tools/network-device-check.js` 已准备好，但由于安装未成功，设备流程未执行，不应记作通过。
- 内置北京移动历史流是否在同事的具体地区、宽带或热点下仍然有效，需要在对应网络下使用新检测入口和实际播放确认。

## 构建入口与保护

- 原入口继续保留；`--isolated-network` 在 `build/network-validation/` 下构建，避开 Windows 下其他构建对 R.jar 的占用。
- `tools/aio.cmd package --qa-network` 构建独立 `.networkcheck` 包，输出位于 `build/network-validation-qa/`。该包仅用于开发验收，不能作为正式升级包发送。
- 正式交付 APK 已验证包名和版本；标准 `app/build/outputs/apk/debug/app-debug.apk` 也已恢复为正式包。
- 修改前接入文件及既有回归测试已备份至 `<workspace>/backups/network-improvement-20261028-1793163036385`。
