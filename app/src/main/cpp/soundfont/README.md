# Embedded SoundFont package

`7f3a9c1d.bin` is an AES-256-GCM package, not a plaintext SoundFont. CMake
verifies its package magic before embedding it into `libxenaudio.so`.

Create or replace it from a local SF2 file without adding the plaintext file to
the repository:

```text
javac -d /tmp/xensynth-sf-packager tools/SoundFontPackager.java
java -cp /tmp/xensynth-sf-packager SoundFontPackager \
  /absolute/path/to/input.sf2 \
  app/src/main/cpp/soundfont/7f3a9c1d.bin
```

`XENSYNTH_SF_KEY_HEX` must contain the 64 hexadecimal characters represented
by the masked native key in `XenAudioEngine.cpp`. Never commit the plaintext
SF2 or the unmasked key.
