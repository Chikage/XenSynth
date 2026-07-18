import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/xensynth_controller.dart';
import '../app/xensynth_settings.dart';
import 'app_palette.dart';
import 'hex/hex_keyboard_view.dart';
import 'spatial/spatial_waterfall_view.dart';
import 'waterfall/waterfall_view.dart';
import 'widgets/control_toolbar.dart';
import 'widgets/settings_panel.dart';

class XenSynthScreen extends StatefulWidget {
  const XenSynthScreen({super.key, this.controller});

  final XenSynthController? controller;

  @override
  State<XenSynthScreen> createState() => _XenSynthScreenState();
}

class _XenSynthScreenState extends State<XenSynthScreen>
    with WidgetsBindingObserver {
  late final XenSynthController _controller;
  final HexKeyboardViewportController _hexKeyboardViewportController =
      HexKeyboardViewportController();
  bool _ownsController = false;
  bool _settingsOpen = false;
  DateTime? _lastBackPress;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _ownsController = widget.controller == null;
    _controller = widget.controller ?? XenSynthController();
    unawaited(_controller.initialize());
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.detached) {
      unawaited(_controller.pause());
      unawaited(_controller.releaseAllNotes());
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) _handleBack();
      },
      child: Scaffold(
        body: AnimatedBuilder(
          animation: _controller,
          builder: (context, _) {
            final settings = _controller.settings;
            final meter = _controller.currentMeter;
            return Stack(
              children: [
                Positioned.fill(
                  child: ColoredBox(
                    color: AppPalette.background,
                    child: Image.asset(
                      'assets/images/waterfall.webp',
                      fit: BoxFit.cover,
                      filterQuality: FilterQuality.low,
                    ),
                  ),
                ),
                Positioned.fill(
                  top: ControlToolbar.height,
                  child: switch (settings.layoutMode) {
                    KeyboardLayoutMode.linear => WaterfallView(
                      score: _controller.score,
                      playhead: _controller.visualPlayhead,
                      edo: settings.edo,
                      // Android shifts score/ruler visuals by the displayed
                      // offset, while playback and touch pitch apply the
                      // inverse offset in the controller.
                      pitchOffsetCents: settings.pitchOffsetCents,
                      tuning: _controller.customTuningActive
                          ? _controller.tuning
                          : null,
                      playing: _controller.waterfallAnimating,
                      duration: _controller.duration,
                      volumeGain: settings.volumeGain,
                      activePitches: _controller.activePitches,
                      onPitchDown: _controller.noteDown,
                      onPitchMove: _controller.noteMove,
                      onPitchUp: _controller.noteUp,
                      onTogglePlayback: _controller.togglePlayback,
                      onSeekStart: _controller.beginSeekGesture,
                      onSeek: _controller.updateSeekGesture,
                      onSeekEnd: _controller.endSeekGesture,
                      onVolumeChanged: _controller.setVolumeGainFromGesture,
                    ),
                    KeyboardLayoutMode.hexagonal => Padding(
                      padding: const EdgeInsets.all(8),
                      child: HexKeyboardView(
                        score: _controller.score,
                        playhead: _controller.visualPlayhead,
                        settings: settings,
                        activePitches: _controller.activePitches,
                        viewportController: _hexKeyboardViewportController,
                        onPitchDown: _controller.noteDown,
                        onPitchMove: _controller.noteMove,
                        onPitchUp: _controller.noteUp,
                      ),
                    ),
                    KeyboardLayoutMode.spatial => Padding(
                      padding: const EdgeInsets.all(8),
                      child: SpatialWaterfallView(
                        score: _controller.score,
                        playhead: _controller.visualPlayhead,
                        settings: settings,
                        activePitches: _controller.activePitches,
                        playing: _controller.waterfallAnimating,
                        viewportController: _hexKeyboardViewportController,
                        onPitchDown: _controller.noteDown,
                        onPitchMove: _controller.noteMove,
                        onPitchUp: _controller.noteUp,
                        onTogglePlayback: _controller.togglePlayback,
                      ),
                    ),
                  },
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  top: 0,
                  child: ControlToolbar(
                    title: _controller.scoreTitle,
                    status: _controller.status,
                    playing: _controller.playing,
                    loading: _controller.loading,
                    position: _controller.playhead,
                    duration: _controller.duration,
                    bpm: _controller.currentBpm,
                    meterNumerator: meter.numerator,
                    meterDenominator: meter.denominator,
                    speed: settings.playbackSpeed,
                    edo: settings.edo,
                    offsetCents: settings.pitchOffsetCents,
                    tuningLabel: _controller.tuningLabel,
                    settingsOpen: _settingsOpen,
                    onOpen: _controller.openDocument,
                    onTogglePlayback: _controller.togglePlayback,
                    onReset: _controller.resetPlayback,
                    onStop: _controller.stop,
                    onSpeedChanged: (value) => _controller.updateSettings(
                      settings.copyWith(playbackSpeed: value),
                    ),
                    onEdoChanged: (value) =>
                        _controller.updateSettings(settings.withEdo(value)),
                    onOffsetChanged: (value) => _controller.updateSettings(
                      settings.copyWith(pitchOffsetCents: value),
                    ),
                    onSettings: () => setState(() {
                      _settingsOpen = !_settingsOpen;
                    }),
                    onResetSettings: _resetSettings,
                    onSeek: _controller.seek,
                    hexKeyboardGesturesEnabled:
                        settings.layoutMode.usesHexKeyboard,
                    onHexKeyboardPan: _hexKeyboardViewportController.panBy,
                    onHexKeyboardZoom: _hexKeyboardViewportController.zoomBy,
                  ),
                ),
                if (!_controller.audioReady)
                  Positioned(
                    left: 10,
                    bottom: 8,
                    child: _StatusBadge(
                      icon: Icons.volume_off_rounded,
                      label: _controller.initialized
                          ? 'VISUAL MODE'
                          : 'AUDIO STARTING',
                    ),
                  ),
                if (_settingsOpen) ...[
                  Positioned.fill(
                    top: ControlToolbar.height,
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => setState(() => _settingsOpen = false),
                      child: ColoredBox(
                        color: Colors.black.withValues(alpha: 0.22),
                      ),
                    ),
                  ),
                  Positioned(
                    top: ControlToolbar.height + 8,
                    right: 8,
                    bottom: 8,
                    child: SettingsPanel(
                      settings: settings,
                      onChanged: _controller.updateSettings,
                      onReset: _resetSettings,
                    ),
                  ),
                ],
                if (_controller.loading)
                  const Positioned.fill(
                    top: ControlToolbar.height,
                    child: IgnorePointer(
                      child: Center(
                        child: ToolSurface(
                          padding: EdgeInsets.all(14),
                          child: SizedBox.square(
                            dimension: 26,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }

  void _handleBack() {
    if (_settingsOpen) {
      setState(() => _settingsOpen = false);
      return;
    }
    if (_controller.playing || _controller.playhead > 0.001) {
      unawaited(_controller.stop());
      return;
    }
    if (defaultTargetPlatform != TargetPlatform.android) return;
    final now = DateTime.now();
    final previous = _lastBackPress;
    if (previous != null &&
        now.difference(previous) < const Duration(milliseconds: 1500)) {
      SystemNavigator.pop();
      return;
    }
    _lastBackPress = now;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        duration: Duration(milliseconds: 1400),
        content: Text('Press back again to exit'),
      ),
    );
  }

  void _resetSettings() {
    _hexKeyboardViewportController.reset();
    _controller.resetSettings();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _hexKeyboardViewportController.dispose();
    if (_ownsController) _controller.dispose();
    super.dispose();
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return ToolSurface(
      color: AppPalette.raisedSurface.withValues(alpha: 0.88),
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: AppPalette.secondaryText),
          const SizedBox(width: 5),
          Text(
            label,
            style: const TextStyle(
              color: AppPalette.secondaryText,
              fontSize: 9,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.7,
            ),
          ),
        ],
      ),
    );
  }
}
