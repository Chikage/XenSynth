# XenSynth Flutter

XenSynth 的 iOS / Android 跨平台版本。界面、乐谱解析、瀑布流和六边形键盘由 Flutter 共享；低延迟合成、乐谱排程、外部 MIDI、文档选择和设置持久化继续复用两端原生实现。

## 功能

- 横屏沉浸式瀑布流与可触摸线性键盘
- 可配置的等距六边形键盘
- Standard MIDI、MIDX 微分音扩展和 MIDI 2.0 Clip 解析
- `.mscz` / `.mscx` 原生转换后导入
- JSON 调律、EDO、音高偏移、速度、音量、混响和延迟设置
- USB / 系统 MIDI 键盘输入，支持延音踏板与音色切换
- Android 系统虚拟 MIDI 输出，其他应用可接收键盘和乐谱播放的 MIDI 流
- 可在设备列表中分别管理 MIDI 输入和输出；支持局域网 RTP-MIDI/AppleMIDI、蓝牙和系统 MIDI 端点
- Android 后台乐谱播放与 MediaSession 通知栏控制（播放、暂停、停止、进度拖动）
- iOS / Android 麦克风录音与实时分析：钢琴音符、YIN 连续基频和 FFT 频谱模式
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

Android 安装后会向系统注册 `XenSynth MIDI Output`（设备支持 Android MIDI 时可见），包含一个 `Output` 端口。其他应用通过系统 MIDI 设备列表连接该端口即可接收实时键盘和乐谱播放事件。微分音使用每个活动音符独立 MIDI 通道的 Pitch Bend 表示，并在音符开始前声明标准 ±2 半音弯音范围；这样同一和弦中的不同音分不会互相改变音高。

Android 乐谱播放由 `XenSynthPlaybackService` 持有 Android MediaSession。开始播放后会显示媒体通知，返回桌面或锁屏不会停止乐谱；通知栏和耳机媒体按键可以播放、暂停、停止和拖动进度。Android 13 及以上首次开始播放时需要允许通知权限。关闭应用任务会按 Android 媒体应用惯例停止播放并清理通知。

## MIDI 设备

设置面板的 `MIDI` 区域在每个设备行中提供 `IN` 和 `OUT` 开关，不再提供独立的全局输入、输出开关。关闭设备输入会断开该 MIDI 来源；关闭设备输出会立即向目标发送 Note Off / All Notes Off，并停止继续向该目标发送键盘和乐谱事件。

`RTP-MIDI / AppleMIDI` 使用 Bonjour `_apple-midi._udp` 自动发现，并从 5004/5005 开始按序选择可用的连续 UDP 控制/数据端口建立双向会话。发现的 XenSynth、JustPiano 或系统 AppleMIDI 端点会显示在 `AVAILABLE MIDI DEVICES` 中；关闭该开关会停止局域网扫描并立即移除缓存的 LAN 端点，但 USB、蓝牙和本机软件端点仍会保留。重新开启时会先保持局域网列表为空，再以新的 Bonjour 扫描结果填充，避免短暂显示旧端点。不再提供固定 host/port 或裸 UDP 兼容路径。Android 使用内置 RTP-MIDI 会话库，iOS 使用系统 CoreMIDI Network Session。本机软件公开的 MIDI 接收端口也会列出；Android 的虚拟 MIDI 设备和 iOS 的 CoreMIDI 虚拟 destination 会标为 `Software`。

## 环境要求

- Flutter 3.44 或兼容的稳定版本（Dart 3.12）
- Android Studio / Android SDK，NDK `28.2.13676358`
- Android 9（API 28）及以上的 `arm64-v8a` 设备
- Xcode 16 或更新版本，iOS 16 及以上

Android 当前仅打包 `arm64-v8a`，因为仓库内复用的 FluidSynth 预编译库为 arm64 版本。

## 麦克风音高识别

`MIC INPUT` 使用无需下载模型的本地 `FFT+YIN` 融合分析，工具栏麦克风按钮负责开始或停止录音。YIN 提供连续基频，FFT 同时提供基频候选、频谱波形和局部峰；两者接近时融合结果，发生可信的八度冲突时由 FFT 辅助纠正。YIN 无结果或运行异常时会自动改用 FFT 音高候选，录音和音高线不会因此中断。

线性瀑布会在刻度尺上方绘制 128 点实时频谱及时间方向的频谱轨迹。每个局部峰吸附到最近的当前 EDO 分隔线，以峰值强度绘制竖线，并在上方使用 POTD 规则标注音名。频谱叠加仅出现在线性视图，但麦克风识别不会锁定布局或禁用 `HEX` / `3D`。

`Mic sensitivity` 可在 50%–200% 之间调整进入分析器的输入增益；保存和重放的 PCM 仍保留麦克风原始幅度。

开始录音会清空当前瀑布流，并锁定打开文件、播放、回到开头和停止按钮；麦克风按钮仍可用于结束录音。结束后这些按钮恢复，播放按钮会重放本次 PCM 录音，并同步重绘识别音符或 FFT 频谱。保存按钮在直接保存成功，或停止播放后选择保存、丢弃、取消时恢复为麦克风按钮，可立即开始下一次识别。在线性刻度尺和 3D 模式中，触摸、麦克风识别和外接 MIDI 的实时音高统一绘制为从键面向上运动的反向瀑布流；亮刻度保持到音符释放，长度反映持续时间，刻度高度和轨迹粗细反映力度。2D HEX 不绘制向上轨迹，改为按输入力度实时调整对应六边形按键的亮度。录音期间不再叠加临时乐谱的旧细线。

融合识别每次输出一个连续基频，不能把和弦或同时发声自动分离为多个录音音符。复杂泛音和强噪声仍可能造成倍频或半频误判；频谱中的多个峰用于可视化，不会额外写入反向音符流。

音频录制、分析和重放均在设备本地完成。FFT 与 YIN 在两端共用相同的 16 kHz 输入帧；iOS 录音会将原始录音和识别结果保存到 Files 中的 `Xen Synth/XenSynth` 目录。iOS 音频会话在合成时使用播放模式，在识别时切换到录放模式并保持扬声器输出。

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
