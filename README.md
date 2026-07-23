# XenSynth Flutter

XenSynth 的 iOS / Android 跨平台版本。界面、乐谱解析、瀑布流和六边形键盘由 Flutter 共享；低延迟合成、乐谱排程、外部 MIDI、文档选择和设置持久化继续复用两端原生实现。

## 功能

- 横屏沉浸式瀑布流与可触摸线性键盘
- 可配置的等距六边形键盘
- Standard MIDI、MIDX 微分音扩展和 MIDI 2.0 Clip 解析
- `.mscz` / `.mscx` 原生转换后导入
- JSON 调律、EDO、音高偏移、速度、音量、混响和延迟设置
- USB / 系统 MIDI 键盘输入，支持延音踏板与音色切换
- Android 麦克风录音与实时分析：多音钢琴、YIN 连续基频和 FFT 频谱模式
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

## 麦克风音高识别（Android）

设置面板的 `MIC INPUT` 可选择三种本地分析模式，工具栏麦克风按钮负责开始或停止录音：

- `PIANO`：首次使用下载约 72.3 MB 的 Google Magenta Onsets and Frames TFLite 模型。它支持多音钢琴转录，输出 88 键 NoteOn / NoteOff。
- `YIN`：无需下载模型，直接从 16 kHz 单声道输入估计连续基频。它适用于人声或单音乐器，并能保留微分音偏移；有 EDO 或自定义调律时会映射到最近音级，`EDO=0` 时保持连续音高。
- `FFT`：无需下载模型，在刻度尺上方直接绘制 128 点实时频谱及时间方向的频谱轨迹。FFT 仅用于线性刻度尺视图；选择后会自动切换到 `LINEAR` 并禁用 `HEX` / `3D`。

新安装默认使用 `YIN`。`Mic sensitivity` 可在 50%–200% 之间调整进入分析器的输入增益；保存和重放的 PCM 仍保留麦克风原始幅度。

开始录音会清空当前瀑布流，并锁定打开文件、播放、回到开头和停止按钮；麦克风按钮仍可用于结束录音。结束后这些按钮恢复，播放按钮会重放本次 PCM 录音，并同步重绘识别音符或 FFT 频谱。保存按钮在直接保存成功，或停止播放后选择保存、丢弃、取消时恢复为麦克风按钮，可立即开始下一次识别。在线性刻度尺和 3D 模式中，触摸、麦克风识别和外接 MIDI 的实时音高统一绘制为从键面向上运动的反向瀑布流；亮刻度保持到音符释放，长度反映持续时间，刻度高度和轨迹粗细反映力度。2D HEX 不绘制向上轨迹，改为按输入力度实时调整对应六边形按键的亮度。录音期间不再叠加临时乐谱的旧细线。

`PIANO` 模型输出固定为十二平均律 MIDI 21–108，不会估计连续频率或音分偏差。`YIN` 每次只估计一个基频，不能分离和弦或同时发声的多个音高；复杂泛音和强噪声也可能造成倍频或半频误判。FFT 显示能量分布，不会把频谱峰自动转换成音符。

音频录制、分析和重放均在设备本地完成。当前 `PIANO` 模式迁移自 JustPiano-Android，`YIN` 与 `FFT` 为 XenSynth 新增的本地检测器；iOS 平台会明确报告这些功能不可用，不会请求麦克风权限。

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
