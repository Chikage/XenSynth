import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/xensynth_settings.dart';
import '../app_palette.dart';

class ControlToolbar extends StatelessWidget {
  const ControlToolbar({
    required this.title,
    required this.status,
    required this.playing,
    required this.loading,
    required this.position,
    required this.duration,
    required this.speed,
    required this.edo,
    required this.offsetCents,
    required this.tuningLabel,
    required this.settingsOpen,
    required this.onOpen,
    required this.onTogglePlayback,
    required this.onReset,
    required this.onStop,
    required this.onSpeedChanged,
    required this.onEdoChanged,
    required this.onOffsetChanged,
    required this.onSettings,
    required this.onResetSettings,
    required this.onSeek,
    this.pitchRecognitionAvailable = false,
    this.pitchRecognizing = false,
    this.pitchRecognitionBusy = false,
    this.pitchRecognitionModelReady = false,
    this.pitchRecognitionDownloadProgress = 0,
    this.pitchRecognitionMode = PitchRecognitionMode.yin,
    this.onPitchRecognition,
    this.microphoneTakeReadyForSave = false,
    this.savingMicrophoneTake = false,
    this.onSaveMicrophoneTake,
    this.transportLocked = false,
    this.hexKeyboardGesturesEnabled = false,
    this.onHexKeyboardPan,
    this.onHexKeyboardZoom,
    this.onHexKeyboardInteraction,
    this.bpm = 120,
    this.meterNumerator = 4,
    this.meterDenominator = 4,
    super.key,
  });

  static const double height = 60;

  final String title;
  final String status;
  final bool playing;
  final bool loading;
  final double position;
  final double duration;
  final double speed;
  final int edo;
  final double offsetCents;
  final String tuningLabel;
  final bool settingsOpen;
  final double bpm;
  final int meterNumerator;
  final int meterDenominator;
  final VoidCallback onOpen;
  final VoidCallback onTogglePlayback;
  final VoidCallback onReset;
  final VoidCallback onStop;
  final ValueChanged<double> onSpeedChanged;
  final ValueChanged<int> onEdoChanged;
  final ValueChanged<double> onOffsetChanged;
  final VoidCallback onSettings;
  final VoidCallback onResetSettings;
  final ValueChanged<double> onSeek;
  final bool pitchRecognitionAvailable;
  final bool pitchRecognizing;
  final bool pitchRecognitionBusy;
  final bool pitchRecognitionModelReady;
  final double pitchRecognitionDownloadProgress;
  final PitchRecognitionMode pitchRecognitionMode;
  final VoidCallback? onPitchRecognition;
  final bool microphoneTakeReadyForSave;
  final bool savingMicrophoneTake;
  final VoidCallback? onSaveMicrophoneTake;
  final bool transportLocked;
  final bool hexKeyboardGesturesEnabled;
  final ValueChanged<Offset>? onHexKeyboardPan;
  final ValueChanged<double>? onHexKeyboardZoom;
  final VoidCallback? onHexKeyboardInteraction;

  @override
  Widget build(BuildContext context) {
    final progress = duration <= 0
        ? 0.0
        : (position / duration).clamp(0.0, 1.0);
    final sliderSpeed = speed.clamp(0.2, 4.0).toDouble();
    final sliderEdo = edo.clamp(0, 72);
    final sliderOffset = offsetCents.clamp(-128.0, 128.0).toDouble();
    final downloadPercent = (pitchRecognitionDownloadProgress * 100)
        .clamp(0, 100)
        .round();
    final pitchRecognitionTooltip = switch (pitchRecognitionMode) {
      PitchRecognitionMode.fft => switch ((
        pitchRecognitionBusy,
        pitchRecognizing,
      )) {
        (true, _) => 'Starting FFT spectrum recording',
        (_, true) => 'Stop FFT spectrum recording',
        _ => 'Record and display the microphone FFT spectrum',
      },
      PitchRecognitionMode.yin => switch ((
        pitchRecognitionBusy,
        pitchRecognizing,
      )) {
        (true, _) => 'Starting continuous YIN pitch detection',
        (_, true) => 'Stop continuous YIN pitch detection',
        _ => 'Detect continuous monophonic pitch with YIN',
      },
      PitchRecognitionMode.piano => switch ((
        pitchRecognitionBusy,
        pitchRecognizing,
        pitchRecognitionModelReady,
      )) {
        (true, _, _) => 'Preparing piano recognition · $downloadPercent%',
        (_, true, _) => 'Stop piano note recognition',
        (_, _, false) => 'Download model and recognize piano notes',
        _ => 'Recognize piano notes from microphone',
      },
    };
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 1000;
        final safePadding = MediaQuery.paddingOf(context);
        final controlSize = compact ? 44.0 : 46.0;
        final gap = compact ? 4.0 : 6.0;
        return Material(
          color: Colors.transparent,
          child: SizedBox(
            height: height,
            child: DecoratedBox(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Color(0xF03B3E3D),
                    Color(0xF2272D2C),
                    Color(0xFA171D1C),
                  ],
                  stops: [0, 0.42, 1],
                ),
                border: Border(
                  top: BorderSide(color: Color(0x2EFFFFFF)),
                  bottom: BorderSide(color: Color(0xB52F3C3A)),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Color(0x79000000),
                    blurRadius: 9,
                    offset: Offset(0, 3),
                  ),
                ],
              ),
              child: Column(
                children: [
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(
                        safePadding.left + (compact ? 6 : 9),
                        3,
                        safePadding.right + (compact ? 6 : 9),
                        3,
                      ),
                      child: Row(
                        children: [
                          _IconToolButton(
                            size: controlSize,
                            tooltip: 'Open score or tuning',
                            icon: Icons.folder_open_rounded,
                            onPressed: transportLocked ? null : onOpen,
                          ),
                          _ToolbarDivider(
                            compact: compact,
                            controlHeight: controlSize,
                          ),
                          _IconToolButton(
                            size: controlSize,
                            tooltip: playing ? 'Pause' : 'Play',
                            icon: loading
                                ? Icons.hourglass_top_rounded
                                : playing
                                ? Icons.pause_rounded
                                : Icons.play_arrow_rounded,
                            active: playing || loading,
                            onPressed: transportLocked
                                ? null
                                : onTogglePlayback,
                          ),
                          SizedBox(width: gap),
                          _IconToolButton(
                            size: controlSize,
                            tooltip: 'Back to start',
                            icon: Icons.restart_alt_rounded,
                            onPressed: transportLocked ? null : onReset,
                          ),
                          SizedBox(width: gap),
                          _IconToolButton(
                            size: controlSize,
                            tooltip: 'Stop and release notes',
                            icon: Icons.stop_rounded,
                            onPressed: transportLocked ? null : onStop,
                          ),
                          if (pitchRecognitionAvailable &&
                              (onPitchRecognition != null ||
                                  (microphoneTakeReadyForSave &&
                                      onSaveMicrophoneTake != null))) ...[
                            SizedBox(width: gap),
                            if (microphoneTakeReadyForSave &&
                                onSaveMicrophoneTake != null)
                              _IconToolButton(
                                key: const ValueKey(
                                  'toolbar-save-microphone-take-button',
                                ),
                                size: controlSize,
                                tooltip: savingMicrophoneTake
                                    ? 'Saving microphone recording'
                                    : 'Save recording and recognized-pitch audio',
                                icon: savingMicrophoneTake
                                    ? Icons.hourglass_top_rounded
                                    : Icons.save_alt_rounded,
                                active: savingMicrophoneTake,
                                onPressed: savingMicrophoneTake
                                    ? null
                                    : onSaveMicrophoneTake,
                              )
                            else
                              _IconToolButton(
                                key: const ValueKey(
                                  'toolbar-pitch-recognition-button',
                                ),
                                size: controlSize,
                                tooltip: pitchRecognitionTooltip,
                                icon: pitchRecognitionBusy
                                    ? pitchRecognitionMode ==
                                              PitchRecognitionMode.piano
                                          ? Icons.downloading_rounded
                                          : Icons.graphic_eq_rounded
                                    : pitchRecognizing
                                    ? Icons.mic_rounded
                                    : Icons.mic_none_rounded,
                                active:
                                    pitchRecognizing || pitchRecognitionBusy,
                                onPressed: pitchRecognitionBusy
                                    ? null
                                    : onPitchRecognition,
                              ),
                          ],
                          _ToolbarDivider(
                            compact: compact,
                            controlHeight: controlSize,
                          ),
                          _MetricEditorGroup(
                            compact: compact,
                            gap: gap,
                            controlHeight: controlSize,
                            speed: sliderSpeed,
                            edo: sliderEdo,
                            offset: sliderOffset,
                            onSpeedChanged: (value) => onSpeedChanged(
                              _snapToStep(value, min: 0.2, max: 4, step: 0.05),
                            ),
                            onEdoChanged: onEdoChanged,
                            onOffsetChanged: onOffsetChanged,
                          ),
                          _ToolbarDivider(
                            compact: compact,
                            controlHeight: controlSize,
                          ),
                          GestureDetector(
                            onLongPress: onResetSettings,
                            child: _IconToolButton(
                              size: controlSize,
                              tooltip: 'Settings · hold to reset',
                              icon: Icons.settings_rounded,
                              active: settingsOpen,
                              onPressed: onSettings,
                            ),
                          ),
                          _ToolbarDivider(
                            compact: compact,
                            controlHeight: controlSize,
                          ),
                          Expanded(
                            child: _HexKeyboardViewportGestureArea(
                              enabled: hexKeyboardGesturesEnabled,
                              onPan: onHexKeyboardPan,
                              onZoom: onHexKeyboardZoom,
                              onInteraction: onHexKeyboardInteraction,
                              child: _ScoreSummary(
                                title: title,
                                status: status,
                                tuningLabel: tuningLabel,
                                position: position,
                                duration: duration,
                                bpm: bpm,
                                meterNumerator: meterNumerator,
                                meterDenominator: meterDenominator,
                                compact: compact,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  _SeekBar(
                    progress: progress,
                    position: position,
                    duration: duration,
                    onSeek: onSeek,
                    enabled: !transportLocked,
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  static double _snapToStep(
    double value, {
    required double min,
    required double max,
    required double step,
  }) {
    final clamped = value.clamp(min, max).toDouble();
    final snapped = min + ((clamped - min) / step).round() * step;
    return snapped.clamp(min, max).toDouble();
  }
}

class _HexKeyboardViewportGestureArea extends StatefulWidget {
  const _HexKeyboardViewportGestureArea({
    required this.enabled,
    required this.onPan,
    required this.onZoom,
    required this.onInteraction,
    required this.child,
  });

  final bool enabled;
  final ValueChanged<Offset>? onPan;
  final ValueChanged<double>? onZoom;
  final VoidCallback? onInteraction;
  final Widget child;

  @override
  State<_HexKeyboardViewportGestureArea> createState() =>
      _HexKeyboardViewportGestureAreaState();
}

class _HexKeyboardViewportGestureAreaState
    extends State<_HexKeyboardViewportGestureArea> {
  double _previousScale = 1;
  bool _interactionHapticSent = false;

  @override
  Widget build(BuildContext context) {
    final enabled =
        widget.enabled && widget.onPan != null && widget.onZoom != null;
    return Semantics(
      hint: enabled ? 'Drag to pan the hex keyboard; pinch to zoom' : null,
      child: MouseRegion(
        cursor: enabled ? SystemMouseCursors.grab : MouseCursor.defer,
        child: GestureDetector(
          key: const ValueKey('toolbar-hex-viewport-gesture-area'),
          behavior: HitTestBehavior.opaque,
          onScaleStart: enabled ? _handleScaleStart : null,
          onScaleUpdate: enabled ? _handleScaleUpdate : null,
          onScaleEnd: enabled ? (_) => _resetScaleGesture() : null,
          child: widget.child,
        ),
      ),
    );
  }

  void _handleScaleStart(ScaleStartDetails details) {
    _previousScale = 1;
    _interactionHapticSent = false;
  }

  void _handleScaleUpdate(ScaleUpdateDetails details) {
    final pan = Offset(details.focalPointDelta.dx, -details.focalPointDelta.dy);
    final factor = details.scale / _previousScale;
    final hasZoom = factor.isFinite && factor > 0 && factor != 1;
    if (!_interactionHapticSent && (pan != Offset.zero || hasZoom)) {
      widget.onInteraction?.call();
      _interactionHapticSent = true;
    }
    if (pan != Offset.zero) widget.onPan!(pan);

    _previousScale = details.scale;
    if (hasZoom) widget.onZoom!(factor);
  }

  void _resetScaleGesture() {
    _previousScale = 1;
    _interactionHapticSent = false;
  }
}

class _ScoreSummary extends StatelessWidget {
  const _ScoreSummary({
    required this.title,
    required this.status,
    required this.tuningLabel,
    required this.position,
    required this.duration,
    required this.bpm,
    required this.meterNumerator,
    required this.meterDenominator,
    required this.compact,
  });

  final String title;
  final String status;
  final String tuningLabel;
  final double position;
  final double duration;
  final double bpm;
  final int meterNumerator;
  final int meterDenominator;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final safeBpm = bpm.isFinite && bpm > 0 ? bpm : 120.0;
    final playbackSummary =
        '${safeBpm.toStringAsFixed(1)} BPM | '
        '$meterNumerator/$meterDenominator | '
        '${_time(position)}/${_time(duration)}';
    final textStyle =
        Typography.material2021(
          platform: Theme.of(context).platform,
        ).white.bodyMedium!.copyWith(
          color: AppPalette.playbackMuted,
          fontSize: 12,
          height: 14 / 12,
          fontWeight: FontWeight.w400,
          letterSpacing: 0,
        );
    return Tooltip(
      message: '$tuningLabel · $status',
      child: Padding(
        padding: EdgeInsetsDirectional.only(start: 2, end: compact ? 6 : 3),
        child: SizedBox(
          width: double.infinity,
          height: 40,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 192),
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.clip,
                  textAlign: TextAlign.end,
                  style: textStyle,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                playbackSummary,
                maxLines: 1,
                overflow: TextOverflow.clip,
                textAlign: TextAlign.end,
                style: textStyle,
              ),
            ],
          ),
        ),
      ),
    );
  }

  static String _time(double seconds) {
    if (!seconds.isFinite || seconds < 0) return '0:00';
    final totalSeconds = seconds.floor();
    final minutes = totalSeconds ~/ 60;
    final remainder = totalSeconds % 60;
    return '$minutes:${remainder.toString().padLeft(2, '0')}';
  }
}

class _IconToolButton extends StatelessWidget {
  const _IconToolButton({
    required this.size,
    required this.tooltip,
    required this.icon,
    required this.onPressed,
    this.active = false,
    super.key,
  });

  final double size;
  final String tooltip;
  final IconData icon;
  final VoidCallback? onPressed;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(9);
    return Tooltip(
      message: tooltip,
      child: Material(
        color: Colors.transparent,
        borderRadius: radius,
        child: Ink(
          width: size,
          height: size,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: active
                  ? const [Color(0xD94B6869), Color(0xE52B4E50)]
                  : const [Color(0x734D5251), Color(0xA52A302F)],
            ),
            border: Border.all(
              color: active
                  ? AppPalette.accent.withValues(alpha: 0.55)
                  : Colors.white.withValues(alpha: 0.08),
            ),
            borderRadius: radius,
            boxShadow: const [
              BoxShadow(
                color: Color(0x42000000),
                blurRadius: 3,
                offset: Offset(0, 1),
              ),
            ],
          ),
          child: InkWell(
            borderRadius: radius,
            onTap: onPressed,
            child: Icon(
              icon,
              size: size * 0.56,
              color: onPressed == null
                  ? AppPalette.secondaryText
                  : active
                  ? AppPalette.primaryText
                  : const Color(0xFFF3F5F4),
            ),
          ),
        ),
      ),
    );
  }
}

class _ToolbarDivider extends StatelessWidget {
  const _ToolbarDivider({required this.compact, required this.controlHeight});

  final bool compact;
  final double controlHeight;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 1,
      height: controlHeight - 8,
      margin: EdgeInsets.symmetric(horizontal: compact ? 5 : 7),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x145B6563), Color(0x8A5B6563), Color(0x145B6563)],
        ),
      ),
    );
  }
}

enum _MetricKind { speed, edo, offset }

class _MetricEditorGroup extends StatefulWidget {
  const _MetricEditorGroup({
    required this.compact,
    required this.gap,
    required this.controlHeight,
    required this.speed,
    required this.edo,
    required this.offset,
    required this.onSpeedChanged,
    required this.onEdoChanged,
    required this.onOffsetChanged,
  });

  final bool compact;
  final double gap;
  final double controlHeight;
  final double speed;
  final int edo;
  final double offset;
  final ValueChanged<double> onSpeedChanged;
  final ValueChanged<int> onEdoChanged;
  final ValueChanged<double> onOffsetChanged;

  @override
  State<_MetricEditorGroup> createState() => _MetricEditorGroupState();
}

class _MetricEditorGroupState extends State<_MetricEditorGroup> {
  final OverlayPortalController _portal = OverlayPortalController();
  final Object _tapGroup = Object();
  final Map<_MetricKind, LayerLink> _links = {
    for (final kind in _MetricKind.values) kind: LayerLink(),
  };
  final Map<_MetricKind, TextEditingController> _controllers = {
    for (final kind in _MetricKind.values) kind: TextEditingController(),
  };
  final Map<_MetricKind, FocusNode> _focusNodes = {
    for (final kind in _MetricKind.values) kind: FocusNode(),
  };
  final Map<_MetricKind, double> _values = {};
  final Map<_MetricKind, double> _lastEmitted = {};
  _MetricKind? _active;

  @override
  void initState() {
    super.initState();
    _syncExternal(forceText: true);
    for (final entry in _focusNodes.entries) {
      entry.value.addListener(() => _handleFocusChanged(entry.key));
    }
  }

  @override
  void didUpdateWidget(covariant _MetricEditorGroup oldWidget) {
    super.didUpdateWidget(oldWidget);
    _syncExternal(forceText: false);
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    for (final node in _focusNodes.values) {
      node.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return CallbackShortcuts(
      bindings: <ShortcutActivator, VoidCallback>{
        const SingleActivator(LogicalKeyboardKey.escape): _dismiss,
      },
      child: TapRegion(
        groupId: _tapGroup,
        onTapOutside: (_) => _dismiss(),
        child: OverlayPortal.overlayChildLayoutBuilder(
          controller: _portal,
          overlayChildBuilder: _buildPopup,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildControl(
                _MetricKind.speed,
                _controlWidth(_MetricKind.speed),
              ),
              SizedBox(width: widget.gap),
              _buildControl(_MetricKind.edo, _controlWidth(_MetricKind.edo)),
              SizedBox(width: widget.gap),
              _buildControl(
                _MetricKind.offset,
                _controlWidth(_MetricKind.offset),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildControl(_MetricKind kind, double width) {
    final radius = BorderRadius.circular(9);
    return CompositedTransformTarget(
      link: _links[kind]!,
      child: Tooltip(
        message: '${_semanticLabel(kind)} · ${_displayText(kind)}',
        child: DecoratedBox(
          key: _controlKey(kind),
          decoration: _metricDecoration(radius),
          child: SizedBox(
            width: width,
            height: widget.controlHeight,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(6, 3, 6, 3),
              child: Column(
                children: [
                  Expanded(
                    child: InkWell(
                      key: _triggerKey(kind),
                      borderRadius: const BorderRadius.vertical(
                        top: Radius.circular(8),
                      ),
                      onTap: () => _toggle(kind),
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: Text(
                          _title(kind),
                          maxLines: 1,
                          style: const TextStyle(
                            color: AppPalette.playbackMuted,
                            fontSize: 8.5,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 0.7,
                            height: 1,
                          ),
                        ),
                      ),
                    ),
                  ),
                  SizedBox(
                    height: 20,
                    child: TextField(
                      key: _inputKey(kind),
                      controller: _controllers[kind],
                      focusNode: _focusNodes[kind],
                      maxLines: 1,
                      textAlign: TextAlign.center,
                      textAlignVertical: TextAlignVertical.center,
                      textInputAction: TextInputAction.done,
                      keyboardType: _keyboardType(kind),
                      inputFormatters: [
                        _SanitizingTextInputFormatter(_sanitizer(kind)),
                      ],
                      onTap: () => _handleFieldTap(kind),
                      onChanged: (text) => _handleTextChanged(kind, text),
                      onSubmitted: (_) => _focusNodes[kind]!.unfocus(),
                      cursorColor: AppPalette.accent,
                      style: const TextStyle(
                        color: AppPalette.primaryText,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        height: 1.15,
                      ),
                      decoration: const InputDecoration(
                        isCollapsed: true,
                        border: InputBorder.none,
                        contentPadding: EdgeInsets.zero,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildPopup(BuildContext context, OverlayChildLayoutInfo layout) {
    final kind = _active;
    if (kind == null) return const SizedBox.shrink();
    final safePadding = MediaQuery.paddingOf(context);
    final popupWidth = math.min(
      widget.compact ? 220.0 : 240.0,
      math.max(120.0, layout.overlaySize.width - safePadding.horizontal - 16),
    );
    final speedWidth = _controlWidth(_MetricKind.speed);
    final edoWidth = _controlWidth(_MetricKind.edo);
    final offsetWidth = _controlWidth(_MetricKind.offset);
    final targetLeft = switch (kind) {
      _MetricKind.speed => 0.0,
      _MetricKind.edo => speedWidth + widget.gap,
      _MetricKind.offset => speedWidth + widget.gap + edoWidth + widget.gap,
    };
    final targetWidth = switch (kind) {
      _MetricKind.speed => speedWidth,
      _MetricKind.edo => edoWidth,
      _MetricKind.offset => offsetWidth,
    };
    final groupOrigin = layout.childPaintTransform.getTranslation();
    final desiredLeft =
        groupOrigin.x +
        switch (kind) {
          _MetricKind.speed => targetLeft,
          _MetricKind.edo => targetLeft + (targetWidth - popupWidth) / 2,
          _MetricKind.offset => targetLeft + targetWidth - popupWidth,
        };
    final minimumLeft = safePadding.left + 8;
    final maximumLeft =
        layout.overlaySize.width - safePadding.right - 8 - popupWidth;
    final left = desiredLeft.clamp(minimumLeft, maximumLeft).toDouble();
    final verticalOffset =
        ControlToolbar.height + 4 - (3 + widget.controlHeight);
    final top = groupOrigin.y + widget.controlHeight + verticalOffset;
    return Positioned(
      left: left,
      top: top,
      child: TapRegion(
        groupId: _tapGroup,
        child: Material(
          key: const ValueKey('toolbar-metric-popup'),
          elevation: 9,
          color: AppPalette.raisedSurface.withValues(alpha: 0.98),
          borderRadius: BorderRadius.circular(9),
          child: SizedBox(
            width: popupWidth,
            height: 54,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 2,
                  activeTrackColor: AppPalette.accent,
                  inactiveTrackColor: const Color(0x705B6563),
                  thumbColor: AppPalette.primaryText,
                  overlayColor: AppPalette.accent.withValues(alpha: 0.14),
                  thumbShape: const RoundSliderThumbShape(
                    enabledThumbRadius: 6,
                    elevation: 0,
                    pressedElevation: 1,
                  ),
                  overlayShape: const RoundSliderOverlayShape(
                    overlayRadius: 12,
                  ),
                  tickMarkShape: SliderTickMarkShape.noTickMark,
                ),
                child: Slider(
                  key: _sliderKey(kind),
                  value: _values[kind]!,
                  min: _minimum(kind),
                  max: _maximum(kind),
                  divisions: _divisions(kind),
                  label: _displayText(kind),
                  semanticFormatterCallback: (_) => _displayText(kind),
                  onChangeStart: (_) => _unfocusAll(),
                  onChanged: (value) => _handleSliderChanged(kind, value),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _syncExternal({required bool forceText}) {
    for (final kind in _MetricKind.values) {
      final value = _normalize(kind, _externalValue(kind));
      _values[kind] = value;
      _lastEmitted[kind] = value;
      if (forceText || !(_focusNodes[kind]?.hasFocus ?? false)) {
        _setControllerText(kind, _formattedText(kind, value));
      }
    }
  }

  void _handleFocusChanged(_MetricKind kind) {
    final node = _focusNodes[kind]!;
    if (node.hasFocus) {
      _setControllerText(kind, _editingText(kind, _values[kind]!));
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !node.hasFocus) return;
        final controller = _controllers[kind]!;
        controller.selection = TextSelection(
          baseOffset: 0,
          extentOffset: controller.text.length,
        );
      });
    } else {
      _setControllerText(kind, _formattedText(kind, _values[kind]!));
    }
  }

  void _handleFieldTap(_MetricKind kind) {
    if (_active != null && _active != kind) {
      setState(() => _active = null);
      _portal.hide();
    }
  }

  void _handleTextChanged(_MetricKind kind, String text) {
    final parsed = switch (kind) {
      _MetricKind.speed => double.tryParse(text),
      _MetricKind.edo => int.tryParse(text)?.toDouble(),
      _MetricKind.offset => int.tryParse(text)?.toDouble(),
    };
    if (parsed == null) return;
    final next = _normalize(kind, parsed);
    if (_values[kind] != next) setState(() => _values[kind] = next);
    _emit(kind, next);
  }

  void _handleSliderChanged(_MetricKind kind, double value) {
    final next = _normalize(kind, value);
    setState(() => _values[kind] = next);
    _setControllerText(kind, _formattedText(kind, next));
    _emit(kind, next);
  }

  void _emit(_MetricKind kind, double value) {
    if (_lastEmitted[kind] == value) return;
    _lastEmitted[kind] = value;
    switch (kind) {
      case _MetricKind.speed:
        widget.onSpeedChanged(value);
      case _MetricKind.edo:
        widget.onEdoChanged(value.round());
      case _MetricKind.offset:
        widget.onOffsetChanged(value);
    }
  }

  void _toggle(_MetricKind kind) {
    _unfocusAll();
    if (_active == kind) {
      _dismiss();
      return;
    }
    setState(() => _active = kind);
    if (!_portal.isShowing) _portal.show();
  }

  void _dismiss() {
    _unfocusAll();
    if (_active != null) setState(() => _active = null);
    if (_portal.isShowing) _portal.hide();
  }

  void _unfocusAll() {
    for (final node in _focusNodes.values) {
      if (node.hasFocus) node.unfocus();
    }
  }

  void _setControllerText(_MetricKind kind, String text) {
    final controller = _controllers[kind]!;
    if (controller.text == text) return;
    controller.value = TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }

  double _externalValue(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => widget.speed,
    _MetricKind.edo => widget.edo.toDouble(),
    _MetricKind.offset => widget.offset,
  };

  double _normalize(_MetricKind kind, double value) => switch (kind) {
    _MetricKind.speed => ControlToolbar._snapToStep(
      value,
      min: 0.2,
      max: 4,
      step: 0.05,
    ),
    _MetricKind.edo => value.round().clamp(0, 72).toDouble(),
    _MetricKind.offset => value.clamp(-128, 128).toDouble(),
  };

  String _editingText(_MetricKind kind, double value) => switch (kind) {
    _MetricKind.speed => value.toStringAsFixed(2),
    _MetricKind.edo => '${value.round()}',
    _MetricKind.offset => '${value.round()}',
  };

  String _formattedText(_MetricKind kind, double value) => switch (kind) {
    _MetricKind.speed => '${value.toStringAsFixed(2)}x',
    _MetricKind.edo => '${value.round()}',
    _MetricKind.offset => '${value.round() > 0 ? '+' : ''}${value.round()} c',
  };

  String _displayText(_MetricKind kind) => _formattedText(kind, _values[kind]!);

  String _title(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => 'SPEED',
    _MetricKind.edo => 'EDO',
    _MetricKind.offset => 'OFFSET',
  };

  String _semanticLabel(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => 'Playback speed',
    _MetricKind.edo => 'Equal divisions of the octave',
    _MetricKind.offset => 'Pitch offset',
  };

  double _controlWidth(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => widget.compact ? 82 : 100,
    _MetricKind.edo => widget.compact ? 74 : 88,
    _MetricKind.offset => widget.compact ? 88 : 106,
  };

  double _minimum(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => 0.2,
    _MetricKind.edo => 0,
    _MetricKind.offset => -128,
  };

  double _maximum(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => 4,
    _MetricKind.edo => 72,
    _MetricKind.offset => 128,
  };

  int? _divisions(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => 76,
    _MetricKind.edo => 72,
    _MetricKind.offset => null,
  };

  TextInputType _keyboardType(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => const TextInputType.numberWithOptions(decimal: true),
    _MetricKind.edo => TextInputType.number,
    _MetricKind.offset => const TextInputType.numberWithOptions(signed: true),
  };

  String Function(String) _sanitizer(_MetricKind kind) => switch (kind) {
    _MetricKind.speed => _sanitizeSpeed,
    _MetricKind.edo => (text) => _sanitizeUnsignedInt(text, 72),
    _MetricKind.offset => (text) => _sanitizeSignedInt(text, 128),
  };

  static String _sanitizeSpeed(String text) {
    var dotSeen = false;
    final filtered = StringBuffer();
    for (final char in text.characters) {
      if (char.codeUnitAt(0) >= 48 && char.codeUnitAt(0) <= 57) {
        filtered.write(char);
      } else if (char == '.' && !dotSeen) {
        filtered.write(char);
        dotSeen = true;
      }
    }
    final value = filtered.toString();
    final dot = value.indexOf('.');
    if (dot < 0) return value.isEmpty ? value : value.substring(0, 1);
    final whole = value.substring(0, dot).characters.take(1).join();
    final decimal = value.substring(dot + 1).characters.take(2).join();
    return '$whole.$decimal';
  }

  static String _sanitizeUnsignedInt(String text, int maxValue) {
    final digits = text
        .replaceAll(RegExp(r'[^0-9]'), '')
        .characters
        .take(maxValue.toString().length)
        .join();
    return digits.isEmpty
        ? digits
        : '${(int.tryParse(digits) ?? 0).clamp(0, maxValue)}';
  }

  static String _sanitizeSignedInt(String text, int maxAbsoluteValue) {
    final sign = text.startsWith('-')
        ? '-'
        : text.startsWith('+')
        ? '+'
        : '';
    final digits = text
        .replaceAll(RegExp(r'[^0-9]'), '')
        .characters
        .take(maxAbsoluteValue.toString().length)
        .join();
    if (digits.isEmpty) return sign;
    final value = (int.tryParse(digits) ?? 0).clamp(0, maxAbsoluteValue);
    return '$sign$value';
  }

  Key _controlKey(_MetricKind kind) => ValueKey('toolbar-${kind.name}-control');
  Key _triggerKey(_MetricKind kind) => ValueKey('toolbar-${kind.name}-trigger');
  Key _inputKey(_MetricKind kind) => ValueKey('toolbar-${kind.name}-input');
  Key _sliderKey(_MetricKind kind) => ValueKey('toolbar-${kind.name}-slider');
}

class _SanitizingTextInputFormatter extends TextInputFormatter {
  _SanitizingTextInputFormatter(this.sanitize);

  final String Function(String text) sanitize;

  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    final text = sanitize(newValue.text);
    return TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }
}

BoxDecoration _metricDecoration(BorderRadius radius) {
  return BoxDecoration(
    gradient: const LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [Color(0x8A343A39), Color(0xA3222928)],
    ),
    border: Border.all(color: const Color(0x5A4A5553)),
    borderRadius: radius,
    boxShadow: const [
      BoxShadow(color: Color(0x3A000000), blurRadius: 2, offset: Offset(0, 1)),
    ],
  );
}

class _SeekBar extends StatelessWidget {
  const _SeekBar({
    required this.progress,
    required this.position,
    required this.duration,
    required this.onSeek,
    this.enabled = true,
  });

  final double progress;
  final double position;
  final double duration;
  final ValueChanged<double> onSeek;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final enabled = this.enabled && duration > 0;
    return Semantics(
      label: 'Playback position',
      value:
          '${_ScoreSummary._time(position)} of '
          '${_ScoreSummary._time(duration)}',
      increasedValue: enabled
          ? _ScoreSummary._time((position + 5).clamp(0, duration))
          : null,
      decreasedValue: enabled
          ? _ScoreSummary._time((position - 5).clamp(0, duration))
          : null,
      onIncrease: enabled
          ? () => onSeek((position + 5).clamp(0, duration))
          : null,
      onDecrease: enabled
          ? () => onSeek((position - 5).clamp(0, duration))
          : null,
      child: MouseRegion(
        cursor: enabled ? SystemMouseCursors.click : MouseCursor.defer,
        child: LayoutBuilder(
          builder: (context, constraints) {
            void seek(double dx) {
              if (!enabled || constraints.maxWidth <= 0) return;
              final fraction = (dx / constraints.maxWidth).clamp(0.0, 1.0);
              onSeek(fraction * duration);
            }

            final thumbLeft = constraints.maxWidth <= 5
                ? 0.0
                : (constraints.maxWidth * progress - 2.5)
                      .clamp(0.0, constraints.maxWidth - 5)
                      .toDouble();
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTapDown: enabled
                  ? (details) => seek(details.localPosition.dx)
                  : null,
              onHorizontalDragStart: enabled
                  ? (details) => seek(details.localPosition.dx)
                  : null,
              onHorizontalDragUpdate: enabled
                  ? (details) => seek(details.localPosition.dx)
                  : null,
              child: SizedBox(
                height: 7,
                child: Stack(
                  alignment: Alignment.centerLeft,
                  children: [
                    Positioned.fill(
                      child: ColoredBox(
                        color: AppPalette.background.withValues(alpha: 0.58),
                      ),
                    ),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: FractionallySizedBox(
                        widthFactor: progress,
                        child: Container(height: 1.5, color: AppPalette.accent),
                      ),
                    ),
                    if (enabled)
                      Positioned(
                        left: thumbLeft,
                        top: 1,
                        child: const DecoratedBox(
                          decoration: BoxDecoration(
                            color: AppPalette.primaryText,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: AppPalette.accent,
                                blurRadius: 3,
                              ),
                            ],
                          ),
                          child: SizedBox.square(dimension: 5),
                        ),
                      ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
