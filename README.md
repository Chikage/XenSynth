# XenSynth Flutter

XenSynth 的 iOS / Android 跨平台版本。界面、乐谱解析、瀑布流和六边形键盘由 Flutter 共享；低延迟合成、乐谱排程、外部 MIDI、文档选择和设置持久化继续复用两端原生实现。

## 功能

- 横屏沉浸式瀑布流与可触摸线性键盘
- 可配置的等距六边形键盘
- Standard MIDI、MIDX 微分音扩展和 MIDI 2.0 Clip 解析
- `.mscz` / `.mscx` 原生转换后导入
- JSON 调律、EDO、音高偏移、速度、音量、混响和延迟设置
- USB / 系统 MIDI 键盘输入，支持延音踏板与音色切换
- FluidSynth SoundFont 合成；Android 使用 Oboe，iOS 使用原生音频排程

## 目录

```text
lib/       Flutter UI、控制器、MIDI/MIDX/MIDI 2.0 解析和调律模型
android/   Kotlin 平台桥、MIDI 输入、C++ Oboe/FluidSynth 音频引擎
ios/       Swift 平台桥、CoreMIDI、FluidSynth 和原生乐谱排程
assets/    演示乐谱、背景图和共享资源
```

平台通道：

```text
MethodChannel  icu.ringona.xensynth/platform
EventChannel   icu.ringona.xensynth/platform/midi
```

## 环境要求

- Flutter 3.44 或兼容的稳定版本（Dart 3.12）
- Android Studio / Android SDK，NDK `28.2.13676358`
- Android 9（API 28）及以上的 `arm64-v8a` 设备
- Xcode 16 或更新版本，iOS 16 及以上

Android 当前仅打包 `arm64-v8a`，因为仓库内复用的 FluidSynth 预编译库为 arm64 版本。

## 运行与构建

```sh
flutter pub get
flutter analyze
flutter test
```

连接设备后运行：

```sh
flutter run -d <device-id>
```

构建 Android 调试 APK：

```sh
flutter build apk --debug
```

输出位置：

```text
build/app/outputs/flutter-apk/app-debug.apk
```

构建 iOS（不签名）：

```sh
flutter build ios --debug --no-codesign
```

构建模拟器版本：

```sh
flutter build ios --simulator --debug
```

发布前请在 Android 和 Xcode 工程中配置自己的签名信息。

## 文件与调律

支持的乐谱扩展名包括 `.mid`、`.midi`、`.kar`、`.midx`、`.midix`、`.midi2`、`.mscz` 和 `.mscx`。JSON 调律格式见 [TUNING_JSON.md](TUNING_JSON.md)。

六边形键盘的来源和整合边界见 [HEX_KEYBOARD_NOTICE.md](HEX_KEYBOARD_NOTICE.md)。

## 许可

项目按 [GPLv3](LICENSE) 发布。FluidSynth 及其预编译框架遵循上游 LGPL 许可；iOS 产物同时打包对应的隐私清单。
