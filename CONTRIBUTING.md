# 参与贡献

请先描述复现步骤、预期行为及设备型号，避免在公开问题中粘贴带账号参数的 M3U、私人截图或完整日志。

提交身份使用你的 GitHub 公开别名和 GitHub noreply 邮箱，保留真实贡献归属。不要冒用仓库所有者身份。只暂存本次修改的具体文件，保留上游版权声明。

提交前执行：

```text
node --test tools/privacy-check.test.js
node tools/privacy-check.js history HEAD
node tools/public-release-check.js
node tools/native-libraries.js verify
```

修改应用后运行 `tools\aio.cmd build --isolated-network`；Linux/macOS 可使用 `./gradlew testDebugUnitTest assembleDebug`。巡检脚本修改后运行 `sh tools/test-playlist-inspect.sh`。Windows 通过原生 CMD 启动 WSL 执行该 Shell 测试。

新的克隆建议先运行 `tools\aio.cmd privacy-install` 安装本机钩子。该命令为仓库维护者配置公开身份；外部贡献者随后用 `git config --local user.name`、`git config --local user.email` 配置自己的公开别名与 noreply 邮箱。CI 会独立检查提交与历史，不能以关闭钩子规避失败。

修改播放器原生库时，更新对应源码、补丁、构建说明和校验值；无法提供对应源码的二进制不可加入。应用独立代码遵循根 MIT；改动带 Apache/LGPL 声明的文件需继续遵守其原有许可。PR 请说明变更、验证结果与已知限制。
