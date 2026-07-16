import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/xensynth_settings.dart';
import '../app_palette.dart';

class SettingsPanel extends StatelessWidget {
  const SettingsPanel({
    required this.settings,
    required this.onChanged,
    required this.onReset,
    super.key,
  });

  final XenSynthSettings settings;
  final ValueChanged<XenSynthSettings> onChanged;
  final VoidCallback onReset;

  static const double width = 300;

  static const _compactButtonStyle = ButtonStyle(
    minimumSize: WidgetStatePropertyAll(Size(0, 34)),
    padding: WidgetStatePropertyAll(
      EdgeInsets.symmetric(horizontal: 10, vertical: 6),
    ),
    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
    visualDensity: VisualDensity.compact,
    textStyle: WidgetStatePropertyAll(TextStyle(fontSize: 10)),
    iconSize: WidgetStatePropertyAll(15),
  );

  @override
  Widget build(BuildContext context) {
    final hexStepMaximum = settings.hexStepMaximum;
    final touchSensitivityPercent = settings.touchSensitivityPercent;
    return Material(
      color: Colors.transparent,
      child: ToolSurface(
        radius: 10,
        color: AppPalette.surface.withValues(alpha: 0.97),
        child: SizedBox(
          width: width,
          child: Column(
            children: [
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(10, 6, 10, 12),
                  children: [
                    const _SectionLabel('SURFACE'),
                    SegmentedButton<KeyboardLayoutMode>(
                      style: _compactButtonStyle,
                      segments: const [
                        ButtonSegment(
                          value: KeyboardLayoutMode.linear,
                          label: Text('LINEAR'),
                          icon: Icon(Icons.waterfall_chart_rounded, size: 15),
                        ),
                        ButtonSegment(
                          value: KeyboardLayoutMode.hexagonal,
                          label: Text('HEX'),
                          icon: Icon(Icons.hive_outlined, size: 15),
                        ),
                      ],
                      selected: {settings.layoutMode},
                      onSelectionChanged: (value) => onChanged(
                        settings.copyWith(layoutMode: value.single),
                      ),
                    ),
                    const _SectionLabel('AUDIO'),
                    _IntegerInputRow(
                      label: 'GM program',
                      value: settings.program,
                      min: 0,
                      max: 127,
                      fieldKey: const ValueKey('gm-program-input'),
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
                      onChanged: (value) =>
                          onChanged(settings.copyWith(volumeGain: value / 100)),
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
                      Row(
                        children: [
                          Expanded(
                            child: _StepperRow(
                              label: 'Q step',
                              value: settings.hexStepQ,
                              min: 1,
                              max: hexStepMaximum,
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexStepQ: value)),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _StepperRow(
                              label: 'R step',
                              value: settings.hexStepR,
                              min: 1,
                              max: hexStepMaximum,
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
                        value: touchSensitivityPercent,
                        min: XenSynthSettings.touchSensitivityPercentMin,
                        max: XenSynthSettings.touchSensitivityPercentMax,
                        divisions: 50,
                        valueLabel: '${touchSensitivityPercent.round()}%',
                        onChanged: (value) => onChanged(
                          settings.copyWith(
                            touchSensitivity:
                                XenSynthSettings.touchSensitivityFromPercent(
                                  value,
                                ),
                          ),
                        ),
                      ),
                      _SliderRow(
                        label: 'Score preview',
                        value: settings.playbackPreviewSeconds,
                        min: XenSynthSettings.playbackPreviewSecondsMin,
                        max: XenSynthSettings.playbackPreviewSecondsMax,
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
                    const SizedBox(height: 8),
                    TextButton.icon(
                      style: _compactButtonStyle,
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
      padding: const EdgeInsets.only(top: 9, bottom: 5),
      child: Text(
        label,
        style: const TextStyle(
          color: AppPalette.accent,
          fontSize: 9,
          fontWeight: FontWeight.w800,
          letterSpacing: 1,
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
    final sliderTheme = SliderTheme.of(context);
    return SizedBox(
      height: 32,
      child: Row(
        children: [
          SizedBox(
            width: 88,
            child: Text(
              label,
              style: const TextStyle(
                color: AppPalette.secondaryText,
                fontSize: 9,
              ),
            ),
          ),
          Expanded(
            child: SliderTheme(
              data: sliderTheme.copyWith(
                trackHeight: 2,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 5),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
              ),
              child: SizedBox(
                height: 28,
                child: Slider(
                  value: value.clamp(min, max),
                  min: min,
                  max: max,
                  divisions: divisions,
                  label: valueLabel,
                  onChanged: onChanged,
                ),
              ),
            ),
          ),
          SizedBox(
            width: 46,
            child: Text(
              valueLabel,
              textAlign: TextAlign.right,
              style: const TextStyle(
                color: AppPalette.primaryText,
                fontSize: 9,
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
      height: 32,
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                color: AppPalette.secondaryText,
                fontSize: 9,
              ),
            ),
          ),
          SizedBox(
            width: 40,
            height: 24,
            child: FittedBox(
              fit: BoxFit.contain,
              child: Switch.adaptive(
                value: value,
                onChanged: onChanged,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _IntegerInputRow extends StatefulWidget {
  const _IntegerInputRow({
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
    this.fieldKey,
  });

  final String label;
  final int value;
  final int min;
  final int max;
  final ValueChanged<int> onChanged;
  final Key? fieldKey;

  @override
  State<_IntegerInputRow> createState() => _IntegerInputRowState();
}

class _IntegerInputRowState extends State<_IntegerInputRow> {
  late final TextEditingController _controller;
  late final FocusNode _focusNode;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: '${widget.value}');
    _focusNode = FocusNode()..addListener(_handleFocusChange);
  }

  @override
  void didUpdateWidget(_IntegerInputRow oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.value != oldWidget.value && !_focusNode.hasFocus) {
      _setText(widget.value);
    }
  }

  void _handleFocusChange() {
    if (!_focusNode.hasFocus) _commit();
  }

  void _handleChanged(String text) {
    final value = int.tryParse(text);
    if (value == null || value < widget.min || value > widget.max) return;
    if (value != widget.value) widget.onChanged(value);
  }

  void _commit() {
    final parsed = int.tryParse(_controller.text);
    final value = (parsed ?? widget.value)
        .clamp(widget.min, widget.max)
        .toInt();
    _setText(value);
    if (value != widget.value) widget.onChanged(value);
  }

  void _setText(int value) {
    final text = '$value';
    if (_controller.text == text) return;
    _controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: ToolSurface(
        color: AppPalette.raisedSurface,
        child: SizedBox(
          height: 32,
          child: Row(
            children: [
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(left: 7),
                  child: Text(
                    widget.label,
                    maxLines: 1,
                    overflow: TextOverflow.fade,
                    softWrap: false,
                    style: const TextStyle(
                      color: AppPalette.secondaryText,
                      fontSize: 9,
                    ),
                  ),
                ),
              ),
              SizedBox(
                width: 58,
                height: 26,
                child: TextField(
                  key: widget.fieldKey,
                  controller: _controller,
                  focusNode: _focusNode,
                  keyboardType: TextInputType.number,
                  textInputAction: TextInputAction.done,
                  textAlign: TextAlign.center,
                  selectAllOnFocus: true,
                  maxLength: widget.max.toString().length,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  cursorColor: AppPalette.accent,
                  style: const TextStyle(
                    color: AppPalette.primaryText,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                  ),
                  decoration: const InputDecoration(
                    counterText: '',
                    isDense: true,
                    contentPadding: EdgeInsets.symmetric(
                      horizontal: 6,
                      vertical: 6,
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: AppPalette.line),
                      borderRadius: BorderRadius.all(Radius.circular(5)),
                    ),
                    focusedBorder: OutlineInputBorder(
                      borderSide: BorderSide(color: AppPalette.accent),
                      borderRadius: BorderRadius.all(Radius.circular(5)),
                    ),
                  ),
                  onChanged: _handleChanged,
                  onSubmitted: (_) => _commit(),
                ),
              ),
              const SizedBox(width: 5),
            ],
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _focusNode
      ..removeListener(_handleFocusChange)
      ..dispose();
    _controller.dispose();
    super.dispose();
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
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: ToolSurface(
        color: AppPalette.raisedSurface,
        child: SizedBox(
          height: 32,
          child: Row(
            children: [
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.only(left: 7),
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.fade,
                    softWrap: false,
                    style: const TextStyle(
                      color: AppPalette.secondaryText,
                      fontSize: 9,
                    ),
                  ),
                ),
              ),
              _CompactIconButton(
                onPressed: value <= min ? null : () => onChanged(value - 1),
                icon: Icons.remove_rounded,
              ),
              SizedBox(
                width: 28,
                child: Text(
                  '$value',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: AppPalette.primaryText,
                    fontSize: 9,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              _CompactIconButton(
                onPressed: value >= max ? null : () => onChanged(value + 1),
                icon: Icons.add_rounded,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CompactIconButton extends StatelessWidget {
  const _CompactIconButton({required this.onPressed, required this.icon});

  final VoidCallback? onPressed;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      onPressed: onPressed,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints.tightFor(width: 28, height: 28),
      visualDensity: VisualDensity.compact,
      icon: Icon(icon, size: 14),
    );
  }
}
