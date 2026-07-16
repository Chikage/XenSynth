import 'package:flutter/material.dart';

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
    super.key,
  });

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

  @override
  Widget build(BuildContext context) {
    final progress = duration <= 0
        ? 0.0
        : (position / duration).clamp(0.0, 1.0);
    return Material(
      color: AppPalette.background.withValues(alpha: 0.92),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: 58,
            child: Row(
              children: [
                const SizedBox(width: 6),
                _IconToolButton(
                  tooltip: 'Open score or tuning',
                  icon: Icons.folder_open_rounded,
                  onPressed: onOpen,
                ),
                const SizedBox(width: 5),
                ToolSurface(
                  color: AppPalette.playbackPanel,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _IconToolButton(
                        tooltip: playing ? 'Pause' : 'Play',
                        icon: loading
                            ? Icons.hourglass_top_rounded
                            : playing
                            ? Icons.pause_rounded
                            : Icons.play_arrow_rounded,
                        active: playing || loading,
                        onPressed: onTogglePlayback,
                      ),
                      _ToolbarDivider(),
                      _IconToolButton(
                        tooltip: 'Back to start',
                        icon: Icons.restart_alt_rounded,
                        onPressed: onReset,
                      ),
                      _ToolbarDivider(),
                      _IconToolButton(
                        tooltip: 'Stop and release notes',
                        icon: Icons.stop_rounded,
                        onPressed: onStop,
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 6),
                _MetricMenu<double>(
                  label: '${_trimDouble(speed)}×',
                  semanticLabel: 'Playback speed',
                  values: const [0.2, 0.5, 0.75, 1, 1.25, 1.5, 2, 3, 4],
                  itemLabel: (value) => '${_trimDouble(value)}×',
                  onSelected: onSpeedChanged,
                ),
                const SizedBox(width: 5),
                _MetricMenu<int>(
                  label: edo == 0 ? 'FREE' : '$edo EDO',
                  semanticLabel: 'Equal divisions of the octave',
                  values: const [0, 12, 19, 22, 24, 26, 31, 41, 53, 72],
                  itemLabel: (value) => value == 0 ? 'FREE' : '$value EDO',
                  onSelected: onEdoChanged,
                ),
                const SizedBox(width: 5),
                _OffsetControl(value: offsetCents, onChanged: onOffsetChanged),
                const SizedBox(width: 7),
                Expanded(
                  child: _ScoreSummary(
                    title: title,
                    status: status,
                    tuningLabel: tuningLabel,
                    position: position,
                    duration: duration,
                  ),
                ),
                GestureDetector(
                  onLongPress: onResetSettings,
                  child: _IconToolButton(
                    tooltip: 'Settings · hold to reset',
                    icon: Icons.tune_rounded,
                    active: settingsOpen,
                    onPressed: onSettings,
                  ),
                ),
                const SizedBox(width: 6),
              ],
            ),
          ),
          SizedBox(
            height: 10,
            child: SliderTheme(
              data: SliderTheme.of(context).copyWith(
                trackHeight: 2,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 4),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 12),
              ),
              child: Slider(
                value: progress,
                onChanged: duration <= 0
                    ? null
                    : (value) => onSeek(value * duration),
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _trimDouble(double value) {
    final fixed = value.toStringAsFixed(2);
    return fixed
        .replaceFirst(RegExp(r'\.0+$'), '')
        .replaceFirst(RegExp(r'0$'), '');
  }
}

class _ScoreSummary extends StatelessWidget {
  const _ScoreSummary({
    required this.title,
    required this.status,
    required this.tuningLabel,
    required this.position,
    required this.duration,
  });

  final String title;
  final String status;
  final String tuningLabel;
  final double position;
  final double duration;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppPalette.primaryText,
              fontSize: 11,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            '$tuningLabel  ${_time(position)} / ${_time(duration)}  $status',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppPalette.secondaryText,
              fontSize: 9,
              letterSpacing: 0.2,
            ),
          ),
        ],
      ),
    );
  }

  static String _time(double seconds) {
    if (!seconds.isFinite || seconds < 0) return '0:00.0';
    final minutes = seconds ~/ 60;
    final remainder = seconds - minutes * 60;
    return '$minutes:${remainder.toStringAsFixed(1).padLeft(4, '0')}';
  }
}

class _IconToolButton extends StatelessWidget {
  const _IconToolButton({
    required this.tooltip,
    required this.icon,
    required this.onPressed,
    this.active = false,
  });

  final String tooltip;
  final IconData icon;
  final VoidCallback onPressed;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip,
      child: IconButton(
        constraints: const BoxConstraints.tightFor(width: 42, height: 42),
        padding: EdgeInsets.zero,
        style: IconButton.styleFrom(
          backgroundColor: active
              ? AppPalette.playbackActive
              : Colors.transparent,
          foregroundColor: active
              ? AppPalette.primaryText
              : AppPalette.secondaryText,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(6)),
        ),
        icon: Icon(icon, size: 22),
        onPressed: onPressed,
      ),
    );
  }
}

class _ToolbarDivider extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(width: 1, height: 24, color: AppPalette.playbackDivider);
  }
}

class _MetricMenu<T> extends StatelessWidget {
  const _MetricMenu({
    required this.label,
    required this.semanticLabel,
    required this.values,
    required this.itemLabel,
    required this.onSelected,
  });

  final String label;
  final String semanticLabel;
  final List<T> values;
  final String Function(T value) itemLabel;
  final ValueChanged<T> onSelected;

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<T>(
      tooltip: semanticLabel,
      color: AppPalette.raisedSurface,
      onSelected: onSelected,
      itemBuilder: (context) => [
        for (final value in values)
          PopupMenuItem<T>(
            value: value,
            height: 38,
            child: Text(itemLabel(value), style: const TextStyle(fontSize: 12)),
          ),
      ],
      child: ToolSurface(
        padding: const EdgeInsets.symmetric(horizontal: 10),
        child: SizedBox(
          height: 42,
          child: Center(
            child: Text(
              label,
              style: const TextStyle(
                color: AppPalette.primaryText,
                fontSize: 11,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _OffsetControl extends StatelessWidget {
  const _OffsetControl({required this.value, required this.onChanged});

  final double value;
  final ValueChanged<double> onChanged;

  @override
  Widget build(BuildContext context) {
    final rounded = value.round();
    return ToolSurface(
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          InkWell(
            borderRadius: BorderRadius.circular(6),
            onTap: () => onChanged((value - 1).clamp(-128, 128)),
            onLongPress: () => onChanged((value - 10).clamp(-128, 128)),
            child: const SizedBox(
              width: 28,
              height: 42,
              child: Icon(Icons.remove_rounded, size: 16),
            ),
          ),
          SizedBox(
            width: 50,
            child: Text(
              '${rounded >= 0 ? '+' : ''}$rounded¢',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: AppPalette.primaryText,
                fontSize: 10,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          InkWell(
            borderRadius: BorderRadius.circular(6),
            onTap: () => onChanged((value + 1).clamp(-128, 128)),
            onLongPress: () => onChanged((value + 10).clamp(-128, 128)),
            child: const SizedBox(
              width: 28,
              height: 42,
              child: Icon(Icons.add_rounded, size: 16),
            ),
          ),
        ],
      ),
    );
  }
}
