# 项目规则

默认用中文交流。本项目为个人项目，维护者默认使用 tools/privacy-policy.json 中的 GitHub 公开身份；外部贡献者使用自己的 GitHub 公开别名和 noreply 邮箱，不能冒用他人或继承公司的身份。

## 个人信息保护

- 禁止提交真实姓名、私人或公司邮箱、本机用户名和绝对路径、电话、设备序列号、MAC、账号凭据、私钥、签名文件、私人截图及定位元数据。
- 文档只用相对路径、环境变量或 `<device-serial>` 占位符；验证记录只保留型号、系统版本及必要的结果。
- 本机交付物、备份和设备证据保存在仓库外；不得复制到版本控制目录。提交前同时检查文件内容、提交说明、作者和提交者。
- 已有上游许可证署名应保留；不要删改署名来通过检查。第三方公开版权行的例外只按文件路径与原文逐行匹配；许可文本按已审查指纹匹配。节目源鉴权参数不设置白名单。
- 第三方源码归档只允许已核验路径和 SHA-256 完全匹配的版本；更新归档时先审查来源，任何未审查的压缩包都会阻止提交。
- 不得用 `--no-verify`、覆盖 hooksPath、删除检查规则或跳过 CI 来绕过隐私检查。检查命中时先脱敏，再提交。
- 在其他目录重新克隆后，先运行 `tools\aio.cmd privacy-install`；Git 不会自动启用克隆仓库中的钩子。
- 若发现历史残留，应报告并在用户授权下清理历史；普通删除提交不能清除旧版本，GitHub 留存对象还可能需要平台支持处理。

## 验证命令

- `node tools/public-release-check.js`：检查开源发行材料和不含鉴权参数的内置列表。
- `node tools/native-libraries.js verify`：校验原生二进制及对应源码归档。
- `sh tools/test-playlist-inspect.sh`：验证巡检脚本；Windows 通过原生 CMD 启动 WSL。

- `tools\aio.cmd privacy-check`：扫描当前提交及所有可达历史。
- `tools\aio.cmd privacy-test`：验证隐私规则和 Git 钩子的阻断行为。
- `tools\aio.cmd build --isolated-network`：仅在修改应用功能时运行项目测试和构建。

Windows 命令通过原生 CMD 入口执行，不使用 PowerShell。完整保护说明见 docs/privacy.md。
