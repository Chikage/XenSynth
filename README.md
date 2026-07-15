# Xen Synth

Xen Synth is an Android landscape app for playing and visualizing microtonal
MIDX / MIDI 2.0 waterfall files.

## Current Scope

- Android package: `icu.ringona.xensynth`
- App label: `Xen Synth`
- Minimum SDK: 24
- Orientation: sensor landscape
- File access: Android Storage Access Framework, plus `ACTION_VIEW` handling for MIDI-like files
- MIDI-related manifest capability: optional `android.software.midi` and USB host feature declarations
- Waterfall engine: OpenGL ES 3.0 renderer with an automatic Canvas fallback
- Supported score inputs: `.mid`, `.midi`, `.midx`, `.midix`, `.midi2`, `.mscz`, `.mscx`
- MuseScore conversion helper: `MsczToMidx` can emit `.midx` with microtonal offsets or plain `.mid/.midi`
- Sound mode: native Oboe / FluidSynth playback with the bundled SoundFont
- Touch layouts: the original linear waterfall ruler or a HexaKeyboard-style
  isomorphic surface, selected and persisted from Settings

The hexagonal surface reuses XenSynth's parsed `ParsedScore`, tuning guide,
transport, program selection, and native audio engine. It does not embed a
second MIDI/MSCZ parser, playback scheduler, synthesizer, or SoundFont. Hex
touches are converted to XenSynth floating-point pitches before they enter the
same per-note tuning and FluidSynth/Oboe path as the linear ruler.

The app no longer embeds a WebView waterfall runtime; score parsing,
visualization, gestures, transport, and audio scheduling all run in the native
Android layer.

## Waterfall Rendering

On devices reporting OpenGL ES 3.0 or newer, the waterfall background, pitch
grid, measure lines, score notes, manual notes, and playhead are rendered by a
batched GLES backend. The existing Android overlay continues to draw the glass
ruler, labels, impacts, and particles. Devices without ES 3.0 keep using the
previous hardware-accelerated Canvas renderer; GLES support is declared as an
optional manifest feature, so it does not filter those devices at install time.

## Build

This repository uses the Gradle wrapper copied from the Android toolchain used by
the related projects. AGP 9 requires Java 17+.

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Audio Notes

The app copies the bundled SoundFont from assets into cache on first use and
loads it into the native FluidSynth backend.

The JustPiano `feature_5.1` native audio stack is the reference for the next
audio milestone:

- `SoundEngineUtil` Java/Kotlin-facing API
- `SoundEngine.cpp` native Oboe / FluidSynth rendering
- SF2 loading via `loadSf2(path)`
- pitch calibration / tuning behavior through FluidSynth key tuning
- on-demand FluidSynth pooling with up to eight instances, providing roughly
  1,920 isolated per-note tuning channels before best-effort channel sharing

The MIDX/MIDI2 parser, renderer, transport, and low-latency audio backend are
now integrated as the primary runtime.

## HexaKeyboard Source

The hexagonal geometry, canvas styling, multi-touch hysteresis, pseudo-pressure,
chord selection, and playback effects were adapted from HexaKeyboard-Android.
See `HEX_KEYBOARD_NOTICE.md` for the exact source revision and integration
boundary.
