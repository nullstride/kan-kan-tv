# 播放器原生库与对应源码

`manifest.json` 记录每个 Maven AAR、官方二进制、补丁后二进制、源码归档和本项目 JNI 库的 SHA-256。

## 对应关系

- `libijkplayer.so`、`libijksdl.so`：与官方 `tv.danmaku.ijk.media:ijkplayer-{armv7a,arm64,x86,x86_64}:0.8.8` 中的文件逐字节一致。源码在 `third_party/sources/ijkplayer-k0.8.8.tar.gz`。
- `libijkffmpeg.so`：同一官方 AAR 的 FFmpeg 库，仅修改 manifest 中列出的三个字节；源码基线为 Bilibili/FFmpeg 的 `ff3.4--ijk0.8.7--20180103--001`，提交 `b26eaa6d8cd0493f3bec47e3bcd40712397afbc8`，完整源码在 `third_party/sources/ffmpeg-ijk0.8.8.tar.gz`。
- `patches/ffmpeg-mp2-decoder.patch` 是等价源码改动：将已编译的浮点 MPEG 音频解码器注册名和 codec ID 改为 MP2，内部 Layer II 解码代码不变；整数 MP3 解码器仍保留。未新增 GPL/nonfree 组件。
- `mp2_jni.c`、`ffmpeg-abi.h` 是本项目当前 JNI 桥接库的完整源码。桥接只通过动态链接调用 FFmpeg；ABI 头依赖上述固定版本，升级 FFmpeg 时必须同步审查。
- `include/jni.h` 来自 Android `libnativehelper` 的 `android-14.0.0_r1`，保留 Apache-2.0 原始声明。

## 校验与重建

使用 Node.js 22 和支持 ELF/Android target 的 Clang/LLD：

```text
node tools/native-libraries.js verify
node tools/native-libraries.js restore
node tools/native-libraries.js build <clang-executable>
```

`restore` 从配置的公共 Maven 镜像获取固定版本 AAR，先验证归档及原始库 SHA-256，再应用 manifest 中的三个字节，最终 SHA-256 也必须匹配。缓存写入被忽略的 `.native-cache/`。`build` 在恢复后，从当前 C 源码构建四种 ABI 的 JNI 桥接库；可传 Android NDK 的 Clang 或通用 Clang/LLD。本库使用编译器自带 C 类型头，无平台系统调用，因此桥接构建不依赖特定 NDK sysroot。编译器版本不同可能改变输出字节，重建后应执行功能验证并审查更新 JNI 校验值。

要从头编译 FFmpeg/ijkplayer，解压两份归档，把 FFmpeg 放入 ijkplayer 的 `android/contrib/ffmpeg-<arch>`，应用源码补丁。使用 ijkplayer README 指定的 NDK r10e 和其 `android/contrib/compile-ffmpeg.sh`、`android/compile-ijk.sh`；`configure-<ABI>.txt` 保留实际二进制中的功能配置，原 CI 构建路径以占位符记录。不同编译器或工具链的输出未承诺逐字节一致。等价源码补丁和精确二进制变换均保留，便于检查及自行修改、替换共享库。

## 分发

这些库采用 LGPL-2.1-or-later。发布 APK 时应同时提供这些源码、补丁、构建说明和许可证；本仓库在每个源码快照中提供它们，并在 APK 的“开源许可”中说明使用 FFmpeg/ijkplayer。不得给第三方 LGPL 部分附加禁止修改或逆向调试的限制。不能把库的 LGPL 许可改为根 MIT 许可。
