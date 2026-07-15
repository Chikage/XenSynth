# HexaKeyboard Integration Notice

The hexagonal keyboard UI in `app/src/main/java/icu/ringona/xensynth/hexkeyboard`
was adapted from:

- Project: HexaKeyboard-Android
- Upstream: <https://github.com/Chikage/HexaKeyboard-Android>
- Source revision: `b372b3b71a2ae671082043bb161774605555652e`
- License: GNU General Public License, version 3

The imported scope is limited to keyboard geometry, drawing, viewport behavior,
touch hit testing, chord selection, pseudo-pressure, and playback visualization.
XenSynth does not import HexaKeyboard-Android's audio engine, MIDI/MIDI2/MSCZ
parsers, playback controller, native dependencies, or bundled SoundFont.
