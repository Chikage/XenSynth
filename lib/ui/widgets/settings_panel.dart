import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/xensynth_settings.dart';
import '../app_palette.dart';

class SettingsPanel extends StatelessWidget {
  const SettingsPanel({
    required this.settings,
    required this.onChanged,
    required this.onReset,
    this.pitchRecognitionAvailable = false,
    super.key,
  });

  final XenSynthSettings settings;
  final ValueChanged<XenSynthSettings> onChanged;
  final VoidCallback onReset;
  final bool pitchRecognitionAvailable;

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
                    _SpacedControl(
                      child: SegmentedButton<KeyboardLayoutMode>(
                        style: _compactButtonStyle,
                        segments: [
                          ButtonSegment(
                            value: KeyboardLayoutMode.linear,
                            label: Text('LINEAR'),
                            icon: Icon(Icons.waterfall_chart_rounded, size: 15),
                          ),
                          ButtonSegment(
                            value: KeyboardLayoutMode.hexagonal,
                            label: Text('HEX'),
                            icon: Icon(Icons.hive_outlined, size: 15),
                            enabled:
                                settings.pitchRecognitionMode !=
                                PitchRecognitionMode.fft,
                          ),
                          ButtonSegment(
                            value: KeyboardLayoutMode.spatial,
                            label: Text('3D'),
                            icon: Icon(Icons.view_in_ar_outlined, size: 15),
                            enabled:
                                settings.pitchRecognitionMode !=
                                PitchRecognitionMode.fft,
                          ),
                        ],
                        selected: {settings.layoutMode},
                        onSelectionChanged: (value) => onChanged(
                          settings.copyWith(layoutMode: value.single),
                        ),
                      ),
                    ),
                    _SliderRow(
                      label: 'Touch vibration',
                      value: settings.hapticFeedbackStrength * 100,
                      min: 0,
                      max: 100,
                      divisions: 3,
                      valueLabel: _hapticStrengthLabel(
                        settings.hapticFeedbackStrength,
                      ),
                      controlKey: const ValueKey('haptic-feedback-slider'),
                      onChanged: (value) => onChanged(
                        settings.copyWith(hapticFeedbackStrength: value / 100),
                      ),
                    ),
                    const _SectionLabel('AUDIO'),
                    _SliderRow(
                      label: 'GM program',
                      value: settings.program.toDouble(),
                      min: 0,
                      max: 127,
                      divisions: 127,
                      valueLabel: '${settings.program}',
                      controlKey: const ValueKey('gm-program-slider'),
                      onChanged: (value) =>
                          onChanged(settings.copyWith(program: value.round())),
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
                    if (pitchRecognitionAvailable) ...[
                      const _SectionLabel('MIC INPUT'),
                      _SpacedControl(
                        child: SegmentedButton<PitchRecognitionMode>(
                          key: const ValueKey('pitch-recognition-mode'),
                          style: _compactButtonStyle,
                          showSelectedIcon: false,
                          segments: const [
                            ButtonSegment(
                              value: PitchRecognitionMode.piano,
                              label: Text('PIANO'),
                              icon: Icon(Icons.piano_rounded, size: 15),
                              tooltip: 'Polyphonic piano note recognition',
                            ),
                            ButtonSegment(
                              value: PitchRecognitionMode.yin,
                              label: Text('YIN'),
                              icon: Icon(Icons.graphic_eq_rounded, size: 15),
                              tooltip: 'Continuous monophonic pitch detection',
                            ),
                            ButtonSegment(
                              value: PitchRecognitionMode.fft,
                              label: Text('FFT'),
                              icon: Icon(
                                Icons.multiline_chart_rounded,
                                size: 15,
                              ),
                              tooltip:
                                  'Live frequency spectrum on the linear ruler',
                            ),
                          ],
                          selected: {settings.pitchRecognitionMode},
                          onSelectionChanged: (value) {
                            final mode = value.single;
                            onChanged(
                              settings.copyWith(
                                pitchRecognitionMode: mode,
                                layoutMode: mode == PitchRecognitionMode.fft
                                    ? KeyboardLayoutMode.linear
                                    : settings.layoutMode,
                              ),
                            );
                          },
                        ),
                      ),
                      _SliderRow(
                        label: 'Mic sensitivity',
                        value: settings.microphoneSensitivity * 100,
                        min: XenSynthSettings.microphoneSensitivityMin * 100,
                        max: XenSynthSettings.microphoneSensitivityMax * 100,
                        divisions: 30,
                        valueLabel:
                            '${(settings.microphoneSensitivity * 100).round()}%',
                        controlKey: const ValueKey(
                          'microphone-sensitivity-slider',
                        ),
                        onChanged: (value) => onChanged(
                          settings.copyWith(microphoneSensitivity: value / 100),
                        ),
                      ),
                    ],
                    if (settings.layoutMode == KeyboardLayoutMode.spatial) ...[
                      const _SectionLabel('3D WATERFALL'),
                      _SpacedControl(
                        child: SegmentedButton<SpatialProjectionMode>(
                          style: _compactButtonStyle.copyWith(
                            minimumSize: const WidgetStatePropertyAll(
                              Size(0, 42),
                            ),
                          ),
                          showSelectedIcon: false,
                          segments: const [
                            ButtonSegment(
                              value: SpatialProjectionMode.cabinet,
                              label: Text(
                                'CABINET\nPROJECTION',
                                textAlign: TextAlign.center,
                              ),
                              tooltip:
                                  'Cabinet projection (1:2 oblique dimetric)',
                            ),
                            ButtonSegment(
                              value: SpatialProjectionMode.obliquePerspective,
                              label: Text(
                                'OBLIQUE\nPERSPECTIVE',
                                textAlign: TextAlign.center,
                              ),
                              tooltip: 'Oblique perspective projection',
                            ),
                          ],
                          selected: {settings.spatialProjection},
                          onSelectionChanged: (value) => onChanged(
                            settings.copyWith(spatialProjection: value.single),
                          ),
                        ),
                      ),
                    ],
                    if (settings.layoutMode.usesHexKeyboard) ...[
                      const _SectionLabel('HEX KEYBOARD'),
                      Row(
                        children: [
                          Expanded(
                            child: _IntegerInputRow(
                              label: 'Columns',
                              labelAbove: true,
                              value: settings.hexColumns,
                              min: 4,
                              max: 64,
                              fieldKey: const ValueKey('hex-columns-input'),
                              onChanged: (value) => onChanged(
                                settings.copyWith(hexColumns: value),
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _IntegerInputRow(
                              label: 'Rows',
                              labelAbove: true,
                              value: settings.hexRows,
                              min: 3,
                              max: 32,
                              fieldKey: const ValueKey('hex-rows-input'),
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexRows: value)),
                            ),
                          ),
                        ],
                      ),
                      Row(
                        children: [
                          Expanded(
                            child: _IntegerInputRow(
                              label: 'Q step',
                              labelAbove: true,
                              value: settings.hexStepQ,
                              min: -hexStepMaximum,
                              max: hexStepMaximum,
                              fieldKey: const ValueKey('hex-q-step-input'),
                              onChanged: (value) =>
                                  onChanged(settings.copyWith(hexStepQ: value)),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _IntegerInputRow(
                              label: 'R step',
                              labelAbove: true,
                              value: settings.hexStepR,
                              min: -hexStepMaximum,
                              max: hexStepMaximum,
                              fieldKey: const ValueKey('hex-r-step-input'),
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
      padding: const EdgeInsets.only(top: 9, bottom: 4),
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

class _SpacedControl extends StatelessWidget {
  const _SpacedControl({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: child,
    );
  }
}

String _hapticStrengthLabel(double strength) {
  if (strength <= 0) return 'OFF';
  if (strength <= 1 / 3) return 'LIGHT';
  if (strength <= 2 / 3) return 'MED';
  return 'STRONG';
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
    this.controlKey,
  });

  final String label;
  final double value;
  final double min;
  final double max;
  final int divisions;
  final String valueLabel;
  final ValueChanged<double> onChanged;
  final Key? controlKey;

  @override
  Widget build(BuildContext context) {
    final sliderTheme = SliderTheme.of(context);
    final currentValue = value.clamp(min, max).toDouble();
    final step = (max - min) / divisions;

    double steppedValue(int direction) {
      return (currentValue + step * direction).clamp(min, max).toDouble();
    }

    return _SpacedControl(
      child: SizedBox(
        height: 36,
        child: Column(
          children: [
            SizedBox(
              height: 10,
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.fade,
                      softWrap: false,
                      style: const TextStyle(
                        color: AppPalette.secondaryText,
                        fontSize: 9,
                        height: 1,
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
                        height: 1,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            SizedBox(
              height: 26,
              child: Row(
                children: [
                  _CompactIconButton(
                    buttonKey: ValueKey('settings-$label-slider-decrease'),
                    tooltip: 'Decrease $label',
                    onPressed: currentValue <= min
                        ? null
                        : () => onChanged(steppedValue(-1)),
                    icon: Icons.remove_rounded,
                    dimension: 26,
                  ),
                  Expanded(
                    child: SliderTheme(
                      data: sliderTheme.copyWith(
                        trackHeight: 2,
                        thumbShape: const RoundSliderThumbShape(
                          enabledThumbRadius: 5,
                        ),
                        overlayShape: const RoundSliderOverlayShape(
                          overlayRadius: 12,
                        ),
                      ),
                      child: SizedBox(
                        height: 26,
                        child: Slider(
                          key: controlKey,
                          value: currentValue,
                          min: min,
                          max: max,
                          divisions: divisions,
                          label: valueLabel,
                          onChanged: onChanged,
                        ),
                      ),
                    ),
                  ),
                  _CompactIconButton(
                    buttonKey: ValueKey('settings-$label-slider-increase'),
                    tooltip: 'Increase $label',
                    onPressed: currentValue >= max
                        ? null
                        : () => onChanged(steppedValue(1)),
                    icon: Icons.add_rounded,
                    dimension: 26,
                  ),
                ],
              ),
            ),
          ],
        ),
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
    return _SpacedControl(
      child: SizedBox(
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
    this.labelAbove = false,
  });

  final String label;
  final int value;
  final int min;
  final int max;
  final ValueChanged<int> onChanged;
  final Key? fieldKey;
  final bool labelAbove;

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
    if (value == null || !_isValueAllowed(value)) return;
    if (value != widget.value) widget.onChanged(value);
  }

  void _step(int delta) {
    final parsed = int.tryParse(_controller.text);
    final current = _normalizedValue(parsed ?? widget.value);
    final value = _normalizedValue(current + delta);
    _setText(value);
    if (value != widget.value) widget.onChanged(value);
  }

  void _commit() {
    final parsed = int.tryParse(_controller.text);
    final value = _normalizedValue(parsed ?? widget.value);
    _setText(value);
    if (value != widget.value) widget.onChanged(value);
  }

  bool _isValueAllowed(int value) {
    return value >= widget.min && value <= widget.max;
  }

  int _normalizedValue(int value) {
    if (_isValueAllowed(value)) return value;
    return value.clamp(widget.min, widget.max).toInt();
  }

  int _stepValue(int delta) {
    final current = _normalizedValue(widget.value);
    return _normalizedValue(current + delta);
  }

  int get _maxInputLength {
    final largestMagnitude = widget.min.abs() > widget.max.abs()
        ? widget.min.abs()
        : widget.max.abs();
    return largestMagnitude.toString().length + (widget.min < 0 ? 1 : 0);
  }

  TextInputFormatter get _integerInputFormatter {
    final pattern = widget.min < 0 ? RegExp(r'^-?\d*$') : RegExp(r'^\d*$');
    return TextInputFormatter.withFunction(
      (oldValue, newValue) =>
          pattern.hasMatch(newValue.text) ? newValue : oldValue,
    );
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
    return _SpacedControl(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.labelAbove)
            Padding(
              padding: const EdgeInsets.only(left: 7, bottom: 2),
              child: _buildLabel(),
            ),
          ToolSurface(
            key: ValueKey('settings-${widget.label}-input-frame'),
            color: AppPalette.raisedSurface,
            child: SizedBox(
              height: 32,
              child: Row(
                mainAxisAlignment: widget.labelAbove
                    ? MainAxisAlignment.center
                    : MainAxisAlignment.start,
                children: [
                  if (!widget.labelAbove)
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.only(left: 7),
                        child: _buildLabel(),
                      ),
                    ),
                  _buildStepButton(
                    delta: -1,
                    icon: Icons.remove_rounded,
                    tooltip: 'Decrease ${widget.label}',
                  ),
                  SizedBox(
                    width: 46,
                    height: 26,
                    child: TextField(
                      key: widget.fieldKey,
                      controller: _controller,
                      focusNode: _focusNode,
                      keyboardType: TextInputType.numberWithOptions(
                        signed: widget.min < 0,
                      ),
                      textInputAction: TextInputAction.done,
                      textAlign: TextAlign.center,
                      selectAllOnFocus: true,
                      maxLength: _maxInputLength,
                      inputFormatters: [_integerInputFormatter],
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
                  _buildStepButton(
                    delta: 1,
                    icon: Icons.add_rounded,
                    tooltip: 'Increase ${widget.label}',
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLabel() {
    return Text(
      widget.label,
      maxLines: 1,
      overflow: TextOverflow.fade,
      softWrap: false,
      style: const TextStyle(color: AppPalette.secondaryText, fontSize: 9),
    );
  }

  Widget _buildStepButton({
    required int delta,
    required IconData icon,
    required String tooltip,
  }) {
    final button = _CompactIconButton(
      buttonKey: ValueKey(
        'settings-${widget.label}-input-${delta < 0 ? 'decrease' : 'increase'}',
      ),
      tooltip: tooltip,
      onPressed: _stepValue(delta) == widget.value ? null : () => _step(delta),
      icon: icon,
    );
    if (!widget.labelAbove) return button;
    return Expanded(child: Center(child: button));
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

class _CompactIconButton extends StatelessWidget {
  const _CompactIconButton({
    required this.onPressed,
    required this.icon,
    this.buttonKey,
    this.tooltip,
    this.dimension = 28,
  });

  final VoidCallback? onPressed;
  final IconData icon;
  final Key? buttonKey;
  final String? tooltip;
  final double dimension;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      key: buttonKey,
      tooltip: tooltip,
      onPressed: onPressed,
      padding: EdgeInsets.zero,
      constraints: BoxConstraints.tightFor(width: dimension, height: dimension),
      visualDensity: VisualDensity.compact,
      style: const ButtonStyle(tapTargetSize: MaterialTapTargetSize.shrinkWrap),
      icon: Icon(icon, size: 14),
    );
  }
}
