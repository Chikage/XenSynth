import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app/xensynth_controller.dart';
import '../app/xensynth_settings.dart';
import 'app_palette.dart';
import 'hex/hex_keyboard_view.dart';
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
            return Stack(
              children: [
                Positioned.fill(
                  child: ColoredBox(
                    color: AppPalette.background,
                    child: Opacity(
                      opacity: 0.14,
                      child: Image.asset(
                        'assets/images/waterfall.webp',
                        fit: BoxFit.cover,
                        filterQuality: FilterQuality.low,
                      ),
                    ),
                  ),
                ),
                Positioned.fill(
                  top: 68,
                  child: settings.layoutMode == KeyboardLayoutMode.linear
                      ? WaterfallView(
                          score: _controller.score,
                          playhead: _controller.playhead,
                          edo: settings.edo,
                          pitchOffsetCents: settings.pitchOffsetCents,
                          activePitches: _controller.activePitches,
                          onPitchDown: _controller.noteDown,
                          onPitchMove: _controller.noteMove,
                          onPitchUp: _controller.noteUp,
                        )
                      : HexKeyboardView(
                          score: _controller.score,
                          playhead: _controller.playhead,
                          settings: settings,
                          activePitches: _controller.activePitches,
                          onPitchDown: _controller.noteDown,
                          onPitchMove: _controller.noteMove,
                          onPitchUp: _controller.noteUp,
                        ),
                ),
                Positioned(
                  left: 0,
                  right: 0,
                  top: 0,
                  child: SafeArea(
                    bottom: false,
                    child: ControlToolbar(
                      title: _controller.scoreTitle,
                      status: _controller.status,
                      playing: _controller.playing,
                      loading: _controller.loading,
                      position: _controller.playhead,
                      duration: _controller.duration,
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
                      onEdoChanged: (value) => _controller.updateSettings(
                        settings.copyWith(edo: value),
                      ),
                      onOffsetChanged: (value) => _controller.updateSettings(
                        settings.copyWith(pitchOffsetCents: value),
                      ),
                      onSettings: () => setState(() {
                        _settingsOpen = !_settingsOpen;
                      }),
                      onResetSettings: _controller.resetSettings,
                      onSeek: _controller.seek,
                    ),
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
                    top: 68,
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () => setState(() => _settingsOpen = false),
                      child: ColoredBox(
                        color: Colors.black.withValues(alpha: 0.22),
                      ),
                    ),
                  ),
                  Positioned(
                    top: 76,
                    right: 8,
                    bottom: 8,
                    child: SettingsPanel(
                      settings: settings,
                      onChanged: _controller.updateSettings,
                      onClose: () => setState(() => _settingsOpen = false),
                      onImportTuning: _controller.importTuning,
                      onReset: _controller.resetSettings,
                    ),
                  ),
                ],
                if (_controller.loading)
                  const Positioned.fill(
                    top: 68,
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

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
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
