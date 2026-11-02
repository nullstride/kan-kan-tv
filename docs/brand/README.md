# 看看电视图标

参考 Apple TV 官网当前图标的视觉语言：石墨黑圆角底、银白字标、极轻微的虹彩光泽。使用自己的播放符号与小写 tv 组合，电视横幅保留「看看电视」名称。

参考：https://www.apple.com/apple-tv-app/ （2026-10-27 查看）

- logo.svg / logo.png：矢量母版与 1024 像素图标。
- logo-preview.png：320 像素预览。
- launcher-*.svg：自适应图标的圆角、圆形、方形裁切参考。
- preview.png：手机桌面与电视横幅效果。

Android 8 及以上使用独立渐变背景与银白前景；Android 13 及以上提供主题单色图标。旧 Android 使用石墨黑底与白色字标。前景核心内容位于自适应图标安全区。

在仓库根目录执行 python tools/generate_tv_banner.py 重新生成全部 Android 资源、SVG、PNG 与电视横幅。需要 Pillow、fontTools、aggdraw，以及本地 Segoe UI Semibold 和微软雅黑字体；第一个参数可替换中文字体路径。字体只用于本地生成轮廓，不随项目分发字体文件。

居中规则：按标识可见轮廓的包围框居中，再向右补偿 0.85 个设计单位、向上补偿 0.05 个单位。Android、SVG、PNG 共用归一化后的路径；电视横幅将字标和中文名称作为一个整体垂直居中。
