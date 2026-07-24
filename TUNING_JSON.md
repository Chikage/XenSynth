# XenSynth 自定义刻度与 MIDI 键盘映射 JSON 规则

XenSynth 使用同一份 JSON 文件定义两类内容：

- `Scale`：线性键盘刻度尺、瀑布流分隔线，以及触摸和连续音高识别的吸附候选音高。
- `Keybind`：外接 MIDI 键盘的按键到目标音高的映射。

二者彼此独立。只写 `Scale` 不会自动建立 MIDI 键位映射；将必填的 `Scale` 写成空 object、仅配置 `Keybind` 条目时，应用仍会自动补上一条基准刻度线。

## 导入方式

1. 将内容保存为 UTF-8 编码的 `.json` 文件。
2. 在 XenSynth 中点击打开文件按钮并选择该文件。
3. 导入成功后，状态栏显示 `TUNING · <profile>`，工具栏的调律名称变为 `profile`。

文件必须是合法 JSON。JSON 不支持注释，也不能在最后一个字段或条目后保留逗号。

## 最小模板

以下是按八度重复的自定义刻度及 MIDI 映射：

```json
{
  "profile": "JUST",
  "type": "octave",
  "offset": 0,
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

## 顶层字段

| 字段 | JSON 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `profile` | string | 否 | 界面显示的调律名称；默认 `TUN`。只识别小写字段名。 |
| `type` / `Type` | string | 否 | `octave` 或 `full`；默认 `octave`。大小写不敏感。 |
| `offset` / `Offset` | number/string | 否 | `octave` 下是全局音分偏移；`full` 下是绝对基准音。 |
| `Scale` / `scale` | object | 是 | 刻度。key 为音分值，value 为视觉比例。 |
| `Keybind` / `keybind` | object | 否 | MIDI 键位映射。key 和 value 的含义取决于 `type`。 |

字段名仅支持表中列出的形式，不是全部大小写均可。例如 `SCALE` 和 `PROFILE` 不会被识别。不要同时写同一字段的大小写版本；同时存在时，程序只采用其中一个。

`Scale` 和 `Keybind` 中的 value 可以是 JSON 数字，也可以是能转换为数字的字符串，例如 `203.91` 和 `"203.91"` 等价。建议始终使用数字。

### profile

`profile` 会过滤控制字符和非 ASCII 字符，并去除首尾空格。过滤后为空时使用 `TUN`，因此名称建议只使用可打印 ASCII 字符。

名称没有解析长度限制。界面以 6 个等宽字符为显示窗口，过长名称会滚动显示。

### type

| 值 | 行为 |
| --- | --- |
| `octave` | 定义一个八度内的刻度，并在全部八度重复。 |
| `full` | 定义 MIDI `0..127` 内的绝对刻度，不自动重复。 |
| 缺失、空值或其它值 | 按 `octave` 处理。 |

## octave 模式

`octave` 是默认模式，适合十二平均律以外但仍按八度循环的音阶。

### octave.offset

`offset` 表示应用的全局音高偏移，单位为 cents（音分）。缺失或空字符串时为 `0`。

数字和带 `c` 后缀的字符串都可以使用：

```json
"offset": 30
```

```json
"offset": "+30c"
```

导入后，该值会写入应用的 pitch offset 设置。界面中的音符位置按该值偏移，实际播放使用相反方向的补偿。

### octave.Scale

`Scale` 的 key 是相对每个八度 C 的音分值：

- 有效范围为 `0 < key < 1200`。
- `0`、负值及 `>= 1200` 的条目会被忽略。
- C 对应的 `0` cents 基准线无需书写，程序会自动以比例 `1` 添加。
- 每个有效刻度会在所有八度重复。
- value 必须是 `0..1` 内的有限数，表示刻度线的相对长度和分隔线强度。
- value 为 `0` 时不绘制该线，但该音高仍保留为吸附候选；如需彻底删除该音高，请删除整个条目。

例如下面的 `203.91` 会在 C4 上方 `203.91` cents、C5 上方 `203.91` cents 等位置重复出现：

```json
{
  "profile": "PENTA",
  "type": "octave",
  "Scale": {
    "203.91": 0.65,
    "386.31": 0.75,
    "701.96": 0.9,
    "884.36": 0.65
  }
}
```

### octave.Keybind

`Keybind` 的 key 是 MIDI 音级，必须是 `0..11` 的整数字符串。映射按八度重复：

| key | MIDI 音级 | key | MIDI 音级 |
| ---: | --- | ---: | --- |
| `0` | C | `6` | F# / Gb |
| `1` | C# / Db | `7` | G |
| `2` | D | `8` | G# / Ab |
| `3` | D# / Eb | `9` | A |
| `4` | E | `10` | A# / Bb |
| `5` | F | `11` | B |

value 是目标音高相对当前八度 C 的音分值，范围为 `0 <= value < 1200`。

计算公式：

```text
pitchClass = midiKey % 12
octaveBase = midiKey - pitchClass
targetMidiPitch = octaveBase + keybindValueCents / 100
```

示例：

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

收到 MIDI D4（键号 `62`）时，程序查找音级 `62 % 12 = 2`，再根据 `Keybind["2"]` 得到 MIDI 浮点音高 `62.0391`。收到 D5（键号 `74`）时，同一规则得到 `74.0391`。

## full 模式

`full` 模式适合不按八度重复，或需要逐个指定绝对音高的调律。`Scale` 中只会出现显式定义的刻度，基准刻度标签为 `O`。

### full.offset

`offset` 是所有 `Scale` 和 `Keybind` value 的绝对基准音。缺失或空字符串时默认为 C4，即 MIDI `60.0`。

支持以下三种格式。

#### 频率

数字或纯数字字符串在 `full` 模式下表示 Hz，而不是 cents。频率必须大于 `0`：

```json
"offset": 440
```

```json
"offset": "440Hz"
```

换算公式：

```text
referenceMidiPitch = 69 + 12 * log2(frequencyHz / 440)
```

例如 `440Hz` 对应 MIDI `69.0`，`261.625565Hz` 约等于 MIDI `60.0`。

#### 音名

格式为：

```text
音名[升降号]八度号[+/-音分偏移[c 或 (c)]]
```

音名支持 `A` 到 `G`（不区分大小写），升降号支持 `#` 或 `b`。八度编号遵循 MIDI 规则：`C-1` 为 MIDI `0`，`C4` 为 MIDI `60`。

```json
"offset": "C4"
```

```json
"offset": "C#4+29.1c"
```

```json
"offset": "B3-25(c)"
```

音分偏移可超过 `50`，程序直接按数值相加。例如 `C4+129.1` 和 `C#4+29.1` 都得到 MIDI `61.291`。

#### 从 C-1 起算的绝对音分

带 `c` 后缀的数字表示从 C-1（MIDI `0`）向上的绝对音分值，不能为负数：

```json
"offset": "6000c"
```

`6000c` 等于 MIDI `60.0`。注意，`"6000"` 没有 `c` 后缀，会被当作 `6000Hz`。

所有 full 基准写法最后都会限制在 MIDI `0..127` 范围内。

### full.Scale

`Scale` 的 key 是相对 `offset` 基准音的音分偏移，可以为负数、`0` 或正数：

```text
lineMidiPitch = referenceMidiPitch + scaleKeyCents / 100
```

规则如下：

- value 必须是 `0..1` 内的有限数，表示刻度线的相对长度和分隔线强度。
- 计算结果超出 MIDI `0..127` 的条目会被忽略。
- 缺少 `"0"` 时，程序会自动添加比例为 `1` 的 `O` 基准线。
- 显式写入 `"0"` 时，可自定义 `O` 线的视觉比例。
- value 为 `0` 时不绘制该线，但仍会作为吸附候选。
- 刻度不会自动在其它八度重复；需要的每一条线都必须显式书写。

示例：

```json
{
  "profile": "FULLC4",
  "type": "full",
  "offset": "C4",
  "Scale": {
    "-1200": 0.45,
    "-700": 0.55,
    "0": 1,
    "386.31": 0.7,
    "701.96": 0.85,
    "1200": 0.45
  }
}
```

此时 `-1200`、`0`、`1200` 分别位于 MIDI `48`、`60`、`72`；程序不会继续自动绘制 MIDI `36` 或 `84`。

### full.Keybind

`Keybind` 的 key 是完整 MIDI 键号，必须是 `0..127` 的整数字符串。value 是相对 `offset` 基准音的音分偏移，可以为负数、`0` 或正数：

```text
targetMidiPitch = referenceMidiPitch + keybindValueCents / 100
```

示例：让 MIDI C4、D4、E4 三个按键触发一组非十二平均律音高：

```json
{
  "profile": "FULLKEY",
  "type": "full",
  "offset": 261.625565,
  "Scale": {
    "0": 1,
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

这里的 `offset` 约等于 C4。MIDI C4（键号 `60`）触发基准音，D4（`62`）触发基准音上方 `203.91` cents，E4（`64`）触发基准音上方 `386.31` cents。

如果将 `offset` 改为 `440`，`Keybind["60"] = 0` 会让 MIDI C4 按键触发 `440Hz` 对应的 A4 音高。

## 映射与优先级

- 自定义 `Scale` 激活后，线性刻度尺使用它取代 EDO 刻度线。
- 触摸输入和连续音高识别会吸附到最近的 `Scale` 音高。
- 外接 MIDI Note On 首先查找 `Keybind`。找到当前键的映射时使用映射音高。
- `Keybind` 中没有当前键时，如果应用启用了非 12 EDO，则回退到 EDO 映射；否则保持原 MIDI 音高。
- `Keybind` 不会改写已导入乐谱中的音符。乐谱回放是否吸附由应用的键盘布局和 pitch snap 设置决定。
- `Scale` 与 `Keybind` 不要求一一对应，但通常应同时列出目标音高，以便听到的音高和看到的刻度一致。
- 应用的其它全局音高偏移、键盘布局吸附等设置仍可能继续影响最终播放音高。

## 解析、忽略与报错规则

| 情况 | 结果 |
| --- | --- |
| 顶层不是 object | 整个文件导入失败。 |
| 缺少 `Scale`，或 `Scale` 不是 object | 整个文件导入失败。 |
| `Keybind` 存在但不是 object | 整个文件导入失败。 |
| `Scale` 的 key/value 不能解析为有限数 | 整个文件导入失败。 |
| `Scale` 的 value 不在 `0..1` | 整个文件导入失败。 |
| `Keybind` 的 key 不是整数，或 value 不是有限数 | 整个文件导入失败。 |
| octave `Scale` key 不在 `(0, 1200)` | 忽略该条目。 |
| octave `Keybind` key 不在 `0..11`，或 value 不在 `[0, 1200)` | 忽略该条目。 |
| full `Scale` 计算出的音高不在 MIDI `0..127` | 忽略该条目。 |
| full `Keybind` key 不在 `0..127`，或目标音高不在 MIDI `0..127` | 忽略该条目。 |
| 未知的顶层字段 | 忽略。 |

为了尽早发现配置错误，建议不要依赖“越界条目会被忽略”的容错行为。

## 编写检查表

保存文件前检查以下内容：

- 使用标准 JSON 双引号，未添加注释或尾随逗号。
- 顶层包含 `Scale` object。
- `octave` 的 `Scale` key 位于 `0` 与 `1200` 之间，且不包含端点。
- `full` 的 `offset` 已明确按 Hz、音名或带 `c` 的绝对音分书写。
- 所有 `Scale` value 都位于 `0..1`。
- `Keybind` key 使用 MIDI 音级 `0..11`（octave）或完整键号 `0..127`（full）。
- MIDI 目标音高与需要显示、吸附的 `Scale` 条目一致。
