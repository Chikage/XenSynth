import 'package:flutter/material.dart';

import '../../app/xensynth_settings.dart';
import '../app_palette.dart';

class SettingsPanel extends StatelessWidget {
  const SettingsPanel({
    required this.settings,
    required this.onChanged,
    required this.onClose,
    required this.onImportTuning,
    required this.onReset,
    super.key,
  });

  final XenSynthSettings settings;
  final ValueChanged<XenSynthSettings> onChanged;
  final VoidCallback onClose;
  final VoidCallback onImportTuning;
  final VoidCallback onReset;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: ToolSurface(
        radius: 12,
        color: AppPalette.surface.withValues(alpha: 0.97),
        child: SizedBox(
          width: 360,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 8, 6, 4),
                child: Row(
                  children: [
                    const Text(
                      'SETTINGS',
                      style: TextStyle(
                        color: AppPalette.primaryText,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.2,
                      ),
                    ),
                    const Spacer(),
                    IconButton(
                      tooltip: 'Close settings',
                      onPressed: onClose,
                      icon: const Icon(Icons.close_rounded, size: 20),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1, color: AppPalette.line),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(12, 10, 12, 18),
                  children: [
                    const _SectionLabel('SURFACE'),
                    SegmentedButton<KeyboardLayoutMode>(
                      segments: const [
                        ButtonSegment(
                          value: KeyboardLayoutMode.linear,
                          label: Text('LINEAR'),
                          icon: Icon(Icons.waterfall_chart_rounded),
                        ),
                        ButtonSegment(
                          value: KeyboardLayoutMode.hexagonal,
                          label: Text('HEX'),
                          icon: Icon(Icons.hive_outlined),
                        ),
                      ],
                      selected: {settings.layoutMode},
                      onSelectionChanged: (value) => onChanged(
                        settings.copyWith(layoutMode: value.single),
                      ),
                    ),
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: onImportTuning,
                      icon: const Icon(Icons.tune_rounded, size: 17),
                      label: const Text('IMPORT TUNING JSON'),
                    ),
                    const _SectionLabel('AUDIO'),
                    _StepperRow(
                      label: 'GM program',
                      value: settings.program,
                      min: 0,
                      max: 127,
                      onChanged: (value) =>
                          onChanged(settings.copyWith(program: value)),
                    ),
                    _SliderRow(
                      label: 'Volume',
                      value: settings.volumeGain * 100,
                      min: 0,
                      max: 100,
                      divisions: 100,
                      valueLabel: '${(settings.volumeGain * 100).round()}%',
                      onChanged: (value) => onChanged(
                        settings.copyWith(volumeGain: value / 100),
                      ),
                    ),
                    _SliderRow(
                      label: 'Reverb',
                      value: settings.reverbMix,
                      min: 0,
                      max: 100,
                      divisions: 100,
                      valueLabel: '${settings.reverbMix.round()}%',
                      onChanged: (value) =>
                          onChanged(settings.copyWith(reverbMix: value)),
                    ),
                    _SliderRow(
                      label: 'Latency',
                      value: settings.audioLatencyMs,
                      min: -100,
                      max: 700,
                      divisions: 160,
                      valueLabel: '${settings.audioLatencyMs.round()} ms',
                      onChanged: (value) =>
                          onChanged(settings.copyWith(audioLatencyMs: value)),
                    ),
                    _SwitchRow(
                      label: 'MIDI controls program',
                      value: settings.externalMidiControlsProgram,
                      onChanged: (value) => onChanged(
                        settings.copyWith(externalMidiControlsProgram: value),
                      ),
                    ),
                    if (settings.layoutMode ==
                        KeyboardLayoutMode.hexagonal) ...[
                      const _SectionLabel('HEX KEYBOARD'),
                      Row(
                        children: [
                          Expanded(
                            child: _StepperRow(
                              label: 'Columns',
                              value: settings.hexColumns,
                              min: 4,
                              max: 64,
                              onChanged: (value) => onChanged(
                                settings.copyWith(hexColumns: value),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _StepperRow(
                              label: 'Rows',
                              value: settings.hexRows,
                              min: 3,
                              max: 32,
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexRows: value)),
                            ),
                          ),
                        ],
                      ),
                      _StepperRow(
                        label: 'Period',
                        value: settings.hexPeriod,
                        min: 1,
                        max: 200,
                        onChanged: (value) =>
                            onChanged(settings.copyWith(hexPeriod: value)),
                      ),
                      Row(
                        children: [
                          Expanded(
                            child: _StepperRow(
                              label: 'Q step',
                              value: settings.hexStepQ,
                              min: -200,
                              max: 200,
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexStepQ: value)),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _StepperRow(
                              label: 'R step',
                              value: settings.hexStepR,
                              min: -200,
                              max: 200,
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexStepR: value)),
                            ),
                          ),
                        ],
                      ),
                      _SliderRow(
                        label: 'Rotation',
                        value: settings.hexRotationDegrees.toDouble(),
                        min: -60,
                        max: 60,
                        divisions: 120,
                        valueLabel: '${settings.hexRotationDegrees}°',
                        onChanged: (value) => onChanged(
                          settings.copyWith(hexRotationDegrees: value.round()),
                        ),
                      ),
                      _SliderRow(
                        label: 'Touch sensitivity',
                        value: settings.touchSensitivity,
                        min: 0,
                        max: 1,
                        divisions: 100,
                        valueLabel:
                            '${(settings.touchSensitivity * 100).round()}%',
                        onChanged: (value) => onChanged(
                          settings.copyWith(touchSensitivity: value),
                        ),
                      ),
                      _SliderRow(
                        label: 'Score preview',
                        value: settings.playbackPreviewSeconds,
                        min: 0.5,
                        max: 8,
                        divisions: 30,
                        valueLabel:
                            '${settings.playbackPreviewSeconds.toStringAsFixed(1)} s',
                        onChanged: (value) => onChanged(
                          settings.copyWith(playbackPreviewSeconds: value),
                        ),
                      ),
                      _SwitchRow(
                        label: 'Pseudo pressure',
                        value: settings.pseudoPressureEnabled,
                        onChanged: (value) => onChanged(
                          settings.copyWith(pseudoPressureEnabled: value),
                        ),
                      ),
                      _SwitchRow(
                        label: 'Snap playback pitch',
                        value: settings.pitchSnapEnabled,
                        onChanged: (value) => onChanged(
                          settings.copyWith(pitchSnapEnabled: value),
                        ),
                      ),
                      _SwitchRow(
                        label: 'Group by octave',
                        value: settings.hexGroupByOctave,
                        onChanged: (value) => onChanged(
                          settings.copyWith(hexGroupByOctave: value),
                        ),
                      ),
                    ],
                    const SizedBox(height: 14),
                    TextButton.icon(
                      onPressed: onReset,
                      icon: const Icon(Icons.settings_backup_restore_rounded),
                      label: const Text('RESTORE DEFAULTS'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 7),
      child: Text(
        label,
        style: const TextStyle(
          color: AppPalette.accent,
          fontSize: 10,
          fontWeight: FontWeight.w800,
          letterSpacing: 1.1,
        ),
      ),
    );
  }
}

class _SliderRow extends StatelessWidget {
  const _SliderRow({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.divisions,
    required this.valueLabel,
    required this.onChanged,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final String valueLabel;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          SizedBox(
            width: 118,
            child: Text(
              label,
              style: const TextStyle(
                color: AppPalette.secondaryText,
                fontSize: 11,
              ),
            ),
          ),
          Expanded(
            child: Slider(
              value: value.clamp(min, max),
              min: min,
              max: max,
              divisions: divisions,
              label: valueLabel,
              onChanged: onChanged,
            ),
          ),
          SizedBox(
            width: 58,
            child: Text(
              valueLabel,
              textAlign: TextAlign.right,
              style: const TextStyle(
                color: AppPalette.primaryText,
                fontSize: 10,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final bool value;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 42,
      child: SwitchListTile.adaptive(
        dense: true,
        contentPadding: EdgeInsets.zero,
        title: Text(
          label,
          style: const TextStyle(color: AppPalette.secondaryText, fontSize: 11),
        ),
        value: value,
        onChanged: onChanged,
      ),
    );
  }
}

class _StepperRow extends StatelessWidget {
  const _StepperRow({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
  });

  final String label;
  final int value;
  final int min;
  final int max;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: ToolSurface(
        color: AppPalette.raisedSurface,
        child: Row(
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsets.only(left: 9),
                child: Text(
                  label,
                  style: const TextStyle(
                    color: AppPalette.secondaryText,
                    fontSize: 10,
                  ),
                ),
              ),
            ),
            IconButton(
              visualDensity: VisualDensity.compact,
              onPressed: value <= min ? null : () => onChanged(value - 1),
              icon: const Icon(Icons.remove_rounded, size: 15),
            ),
            SizedBox(
              width: 34,
              child: Text(
                '$value',
                textAlign: TextAlign.center,
                style: const TextStyle(
                  color: AppPalette.primaryText,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            IconButton(
              visualDensity: VisualDensity.compact,
              onPressed: value >= max ? null : () => onChanged(value + 1),
              icon: const Icon(Icons.add_rounded, size: 15),
            ),
          ],
        ),
      ),
    );
  }
}
