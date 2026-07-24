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
      unawaited(_controller.stopPitchRecognition());
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
            final hideScoreVisualization =
                (_controller.pitchRecognizing &&
                    _controller.hasMicrophoneTake) ||
                _controller.scoreVisualizationSuppressed;
            final visualScore = hideScoreVisualization
                ? null
                : _controller.score;
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
                      score: visualScore,
                      playhead: _controller.visualPlayhead,
                      edo: settings.edo,
                      // Android shifts score/ruler visuals by the displayed
                      // offset, while playback and touch pitch apply the
                      // inverse offset in the controller.
                      pitchOffsetCents: settings.pitchOffsetCents,
                      tuning: _controller.customTuningActive
                          ? _controller.tuning
                          : null,
                      playing:
                          _controller.waterfallAnimating &&
                          !_controller.pitchRecognizing,
                      duration: _controller.duration,
                      volumeGain: settings.volumeGain,
                      activePitches: _controller.activePitches,
                      onPitchDown: _handlePitchDown,
                      onPitchMove: _handlePitchMove,
                      onPitchUp: _controller.noteUp,
                      onTogglePlayback: _controller.togglePlayback,
                      onSeekStart: _controller.beginSeekGesture,
                      onSeek: _controller.updateSeekGesture,
                      onSeekEnd: _controller.endSeekGesture,
                      onVolumeChanged: _controller.setVolumeGainFromGesture,
                      spectrumFrames: _controller.showingFftSpectrum
                          ? _controller.spectrumFrames
                          : const [],
                      pitchInputEvents: _controller.pitchInputEvents,
                      visualizationGeneration:
                          _controller.pitchVisualizationGeneration,
                    ),
                    KeyboardLayoutMode.hexagonal => Padding(
                      padding: const EdgeInsets.all(8),
                      child: HexKeyboardView(
                        score: visualScore,
                        playhead: _controller.visualPlayhead,
                        settings: settings,
                        activePitches: _controller.activePitches,
                        activePitchVelocities:
                            _controller.activePitchVelocities,
                        viewportController: _hexKeyboardViewportController,
                        onControlInteraction: _triggerHapticFeedback,
                        basisEditorVisible: _settingsOpen,
                        onBasisDirectionsChanged: (qDirection, rDirection) {
                          unawaited(
                            _controller.updateSettings(
                              settings.copyWith(
                                hexQDirection: qDirection,
                                hexRDirection: rDirection,
                              ),
                            ),
                          );
                        },
                        onBasisEditorDismissed: () => setState(() {
                          _settingsOpen = false;
                        }),
                        onPitchDown: _handlePitchDown,
                        onPitchMove: _handlePitchMove,
                        onPitchUp: _controller.noteUp,
                      ),
                    ),
                    KeyboardLayoutMode.spatial => Padding(
                      padding: const EdgeInsets.all(8),
                      child: SpatialWaterfallView(
                        score: visualScore,
                        playhead: _controller.visualPlayhead,
                        settings: settings,
                        activePitches: _controller.activePitches,
                        playing:
                            _controller.waterfallAnimating &&
                            !_controller.pitchRecognizing,
                        viewportController: _hexKeyboardViewportController,
                        onControlInteraction: _triggerHapticFeedback,
                        onPitchDown: _handlePitchDown,
                        onPitchMove: _handlePitchMove,
                        onPitchUp: _controller.noteUp,
                        onTogglePlayback: _controller.togglePlayback,
                        pitchInputEvents: _controller.pitchInputEvents,
                        visualizationGeneration:
                            _controller.pitchVisualizationGeneration,
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
                    onStop: _handleStop,
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
                    pitchRecognitionAvailable:
                        _controller.pitchRecognitionAvailable,
                    pitchRecognizing: _controller.pitchRecognizing,
                    pitchRecognitionBusy: _controller.pitchRecognitionBusy,
                    pitchRecognitionModelReady:
                        _controller.pitchRecognitionModelReady,
                    pitchRecognitionDownloadProgress:
                        _controller.pitchRecognitionDownloadProgress,
                    pitchRecognitionMode: settings.pitchRecognitionMode,
                    onPitchRecognition: _togglePitchRecognition,
                    microphoneTakeReadyForSave:
                        _controller.microphoneTakeNeedsSaving,
                    savingMicrophoneTake: _controller.savingMicrophoneTake,
                    onSaveMicrophoneTake: _saveMicrophoneTake,
                    transportLocked: _controller.recordingTransportLocked,
                    hexKeyboardGesturesEnabled:
                        settings.layoutMode.usesHexKeyboard,
                    onHexKeyboardPan: _hexKeyboardViewportController.panBy,
                    onHexKeyboardZoom: _hexKeyboardViewportController.zoomBy,
                    onHexKeyboardInteraction: _triggerHapticFeedback,
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
                if (_controller.pitchRecognitionBusy ||
                    _controller.pitchRecognizing)
                  Positioned(
                    left: 10,
                    bottom: _controller.audioReady ? 8 : 42,
                    child: _StatusBadge(
                      icon: _controller.pitchRecognizing
                          ? Icons.mic_rounded
                          : settings.pitchRecognitionMode ==
                                PitchRecognitionMode.piano
                          ? Icons.downloading_rounded
                          : Icons.graphic_eq_rounded,
                      label: _pitchRecognitionBadgeLabel,
                    ),
                  ),
                if (_settingsOpen) ...[
                  Positioned.fill(
                    top: ControlToolbar.height,
                    child: IgnorePointer(
                      ignoring:
                          settings.layoutMode == KeyboardLayoutMode.hexagonal,
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () => setState(() => _settingsOpen = false),
                        child: ColoredBox(
                          color:
                              settings.layoutMode ==
                                  KeyboardLayoutMode.hexagonal
                              ? Colors.transparent
                              : Colors.black.withValues(alpha: 0.22),
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    top: ControlToolbar.height + 8,
                    right: 8,
                    bottom: 8,
                    child: SettingsPanel(
                      settings: settings,
                      pitchRecognitionAvailable:
                          _controller.pitchRecognitionAvailable,
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

  void _handlePitchDown(int pointer, double pitch, int velocity) {
    _triggerHapticFeedback();
    _controller.noteDown(pointer, pitch, velocity);
  }

  void _handlePitchMove(int pointer, double pitch, int velocity) {
    _triggerHapticFeedback();
    _controller.noteMove(pointer, pitch, velocity);
  }

  Future<void> _togglePitchRecognition() async {
    if (_controller.pitchRecognitionBusy) return;
    if (_controller.pitchRecognizing) {
      await _controller.stopPitchRecognition();
      return;
    }

    var downloadIfNeeded = false;
    if (_controller.settings.pitchRecognitionMode ==
            PitchRecognitionMode.piano &&
        !_controller.pitchRecognitionModelReady) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Piano note recognition'),
          content: const Text(
            'The first use downloads the official Magenta Onsets and Frames '
            'model (about 72.3 MB). After download, microphone audio is '
            'processed locally on this device. Continue?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Download'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
      downloadIfNeeded = true;
    }

    final started = await _controller.startPitchRecognition(
      downloadIfNeeded: downloadIfNeeded,
    );
    if (!started && mounted) {
      final message = _controller.pitchRecognitionMessage.isEmpty
          ? 'Microphone pitch recognition is unavailable.'
          : _controller.pitchRecognitionMessage;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _handleStop() async {
    final shouldOfferSave = _controller.microphoneTakeNeedsSaving;
    await _controller.stop();
    if (!shouldOfferSave || !mounted) return;
    final choice = await showDialog<_MicrophoneTakeStopChoice>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Save microphone recording?'),
        content: const Text(
          'Save the recording and its recognized-pitch audio as two WAV files?',
        ),
        actionsAlignment: MainAxisAlignment.spaceBetween,
        actions: [
          TextButton(
            onPressed: () =>
                Navigator.of(context).pop(_MicrophoneTakeStopChoice.cancel),
            child: const Text('Cancel'),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextButton(
                onPressed: () => Navigator.of(
                  context,
                ).pop(_MicrophoneTakeStopChoice.discard),
                child: const Text('Discard'),
              ),
              const SizedBox(width: 8),
              FilledButton.icon(
                onPressed: () =>
                    Navigator.of(context).pop(_MicrophoneTakeStopChoice.save),
                icon: const Icon(Icons.save_alt_rounded),
                label: const Text('Save'),
              ),
            ],
          ),
        ],
      ),
    );
    if (!mounted) return;
    switch (choice ?? _MicrophoneTakeStopChoice.cancel) {
      case _MicrophoneTakeStopChoice.save:
        await _saveMicrophoneTake();
      case _MicrophoneTakeStopChoice.discard:
        await _controller.discardMicrophoneTake();
      case _MicrophoneTakeStopChoice.cancel:
        _controller.dismissMicrophoneTakeSave();
    }
  }

  Future<void> _saveMicrophoneTake() async {
    final saved = await _controller.saveMicrophoneTake();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          saved
              ? 'Saved two WAV files to Music/XenSynth.'
              : 'Could not save the microphone recording.',
        ),
      ),
    );
  }

  String get _pitchRecognitionBadgeLabel {
    if (_controller.pitchRecognizing) {
      if (_controller.settings.pitchRecognitionMode ==
          PitchRecognitionMode.fft) {
        return 'FFT RECORDING';
      }
      if (_controller.settings.pitchRecognitionMode ==
          PitchRecognitionMode.yin) {
        final frequency = _controller.pitchRecognitionFrequencyHz;
        return frequency == null
            ? 'YIN LISTENING'
            : 'YIN ${frequency.toStringAsFixed(1)} HZ';
      }
      return 'PIANO LISTENING';
    }
    if (_controller.pitchRecognitionPhase == 'downloading') {
      final progress = (_controller.pitchRecognitionDownloadProgress * 100)
          .clamp(0, 100)
          .round();
      return 'MODEL $progress%';
    }
    if (_controller.pitchRecognitionPhase == 'permission') {
      return 'MIC PERMISSION';
    }
    return 'MIC STARTING';
  }

  void _triggerHapticFeedback() {
    final strength = _controller.settings.hapticFeedbackStrength;
    if (strength <= 0) return;
    if (strength <= 1 / 3) {
      unawaited(HapticFeedback.lightImpact());
    } else if (strength <= 2 / 3) {
      unawaited(HapticFeedback.mediumImpact());
    } else {
      unawaited(HapticFeedback.heavyImpact());
    }
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

enum _MicrophoneTakeStopChoice { save, discard, cancel }

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
