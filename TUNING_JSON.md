# 调律绘制 JSON 文件说明

XenSynth 可以导入 JSON 调律文件来定义键盘刻度线、瀑布流分隔线、触摸吸附音高和 MIDI/keybind 音高映射。文件顶层必须是一个 JSON object，其中 `Scale`/`scale` 是必填项。

## 顶层字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `profile` | string | 否 | 调律名称。会显示在界面中，最多保留 8 个可打印 ASCII 字符；缺失时使用 `TUN`。字段名目前只读取小写 `profile`。 |
| `type` / `Type` | string | 否 | 绘制类型。空字符串、缺失或 `"octave"` 都表示 octave 模式；`"full"` 表示 full 模式。 |
| `offset` / `Offset` | number/string | 视模式而定 | octave 模式下表示瀑布整体显示偏移，单位为 cents；full 模式下表示标准音频率，单位为 Hz。 |
| `Scale` / `scale` | object | 是 | 刻度定义。key 是音分值，value 是刻度比例。 |
| `Keybind` / `keybind` | object | 否 | MIDI 输入或谱面音符的音高映射。两种模式的含义不同。 |

`Scale` 和 `Keybind` 的 value 可以写成数字，也可以写成可解析为数字的字符串。

## type: octave

`octave` 是默认模式。它沿用原来的八度循环逻辑：`Scale` 中定义的是一个八度内的刻度，导入后会在不同八度重复绘制。

### offset

octave 模式下，`offset` 是瀑布整体显示偏移，单位为 cents。缺失、空字符串时为 `0`。

可写为：

```json
"offset": 30
```

也可写为：

```json
"offset": "+30c"
```

### Scale

octave 模式下，`Scale` 的 key 是一个八度内相对 C 的音分值：

- 有效范围是大于 `0` 且小于 `1200`。
- `0` 不需要写；系统会自动保留 C 线。
- 每个刻度会在所有八度重复。
- value 必须在 `0..1` 之间，控制键盘刻度线长度和瀑布流分隔线强度。
- value 为 `0` 时相当于隐藏该刻度。

示例：

```json
{
  "profile": "PENTA",
  "type": "octave",
  "offset": 0,
  "Scale": {
    "203.91": 0.65,
    "386.31": 0.75,
    "701.96": 0.9,
    "884.36": 0.65
  }
}
```

### Keybind

octave 模式下，`Keybind` 的 key 是 MIDI 音级，范围为 `0..11`：

| key | MIDI 音级 |
| --- | --- |
| `0` | C |
| `1` | C# / Db |
| `2` | D |
| `...` | ... |
| `11` | B |

value 是该音级在当前八度 C 之上的音分值，范围为 `0 <= value < 1200`。映射会按八度重复。

例如：

```json
{
  "profile": "JUST",
  "type": "octave",
  "Scale": {
    "203.91": 0.7,
    "386.31": 0.7,
    "701.96": 0.9
  },
  "Keybind": {
    "0": 0,
    "2": 203.91,
    "4": 386.31,
    "7": 701.96
  }
}
```

如果收到 MIDI D4，也就是 MIDI 62，`Keybind["2"] = 203.91` 会让它播放为 C4 上方 `203.91` cents 的音高；收到 D5 时会自动映射到 C5 上方同样的音分位置。

## type: full

`full` 模式不再按八度循环。它以 `offset` 指定的频率作为唯一标准音，绘制标签为 `O` 的标准线；不会再显示原来的所有八度 C 线。

### offset

full 模式下，`offset` 是标准音频率，单位为 Hz，并且必须是大于 0 的有限数字。

可写为：

```json
"offset": 440
```

也可写为：

```json
"offset": "440Hz"
```

系统会把频率换算为 MIDI 浮点音高：

```text
midiPitch = 69 + 12 * log2(frequencyHz / 440)
```

例如：

- `440Hz` 对应 MIDI `69.0`。
- `261.625565Hz` 约等于 MIDI `60.0`。

### Scale

full 模式下，`Scale` 的 key 是相对标准音的音分偏移：

- 负值表示标准音下方。
- 正值表示标准音上方。
- `1200` cents 等于一个八度距离。
- value 必须在 `0..1` 之间，控制键盘刻度线长度和瀑布流分隔线强度。
- 计算后的音高必须在 MIDI `0..127` 之内才会绘制；超出范围的刻度会被忽略。
- 如果没有写 `"0"`，系统会自动绘制标准音 `O` 线，比例为 `1`。
- 如果写了 `"0"`，标准音 `O` 线会使用该 value。

full 模式音高计算方式：

```text
linePitch = offsetFrequencyAsMidiPitch + scaleKeyCents / 100
```

示例：以 C4 作为标准音。

```json
{
  "profile": "FULLC4",
  "type": "full",
  "offset": 261.625565,
  "Scale": {
    "-1200": 0.45,
    "-700": 0.55,
    "0": 1.0,
    "386.31": 0.7,
    "701.96": 0.85,
    "1200": 0.45
  }
}
```

在这个例子中：

- `0` 绘制在约 MIDI `60.0`，标签显示为 `O`。
- `1200` 绘制在约 MIDI `72.0`。
- `-1200` 绘制在约 MIDI `48.0`。
- 这些线都是显式写出的 full 刻度，不会自动继续向其它八度重复。

### Keybind

full 模式下，`Keybind` 的 key 是完整 MIDI 键号，范围为 `0..127`。其中 `60` 表示 MIDI 键盘上的 C4 按键。

value 是相对 `offset` 标准音的音分偏移。收到对应 MIDI 键号后，实际播放音高为：

```text
targetPitch = offsetFrequencyAsMidiPitch + keybindValueCents / 100
```

计算后的 `targetPitch` 必须在 MIDI `0..127` 之内。

示例：让 MIDI C4、D4、E4 按键播放标准音、上方 `203.91` cents、上方 `386.31` cents。

```json
{
  "profile": "FULLKEY",
  "type": "full",
  "offset": 261.625565,
  "Scale": {
    "0": 1.0,
    "203.91": 0.7,
    "386.31": 0.7
  },
  "Keybind": {
    "60": 0,
    "62": 203.91,
    "64": 386.31
  }
}
```

如果 `offset` 写成 `440`，那么 `Keybind["60"] = 0` 会让 MIDI C4 按键播放 `440Hz` 对应的音高，而不是传统 C4。

## 触摸和播放

导入调律后，点击键盘刻度区域会吸附到最近的可用刻度线。

- octave 模式会在八度循环后的刻度中寻找最近线。
- full 模式只会在 JSON 中定义出的绝对刻度线中寻找最近线，不会跨八度重复。
- 播放时使用浮点 MIDI 音高，内部会拆成最近的 MIDI 标准音高加音分偏移，以保证微分音精度。

`Keybind` 会影响 MIDI 输入和谱面播放映射；没有写 `Keybind` 时，MIDI 输入保持原音高。

## 常见错误

- `type` 写错：只支持空值、`octave`、`full`。
- full 模式缺少 `offset`：full 必须有标准频率。
- full 模式把 `offset` 当 cents 写：full 下 `offset` 是 Hz，不是音分。
- octave 模式 `Scale` 写负值或 `>=1200`：octave 只接受一个八度内的大于 `0` 且小于 `1200` 的音分。
- full 模式刻度超出 MIDI `0..127`：不会绘制。
- full 模式 `Keybind` 的目标音高超出 MIDI `0..127`：文件会解析失败。
- `Scale` / `Keybind` 的比例或音分写成无法解析的字符串：文件会解析失败。
