import 'package:flutter/material.dart';

abstract final class AppPalette {
  static const background = Color(0xFF0E1313);
  static const surface = Color(0xFF161D1C);
  static const raisedSurface = Color(0xFF1B2221);
  static const line = Color(0xFF384A47);
  static const primaryText = Color(0xFFEDF5F2);
  static const secondaryText = Color(0xFF9BAEAA);
  static const accent = Color(0xFF40C7CC);
  static const selection = Color(0xFFFF9C45);
  static const outline = Color(0xFFAEABFF);
  static const danger = Color(0xFFFF6B6B);

  static const playbackButton = Color(0x6B262B34);
  static const playbackPanel = Color(0x5220252D);
  static const playbackActive = Color(0xBD1B6672);
  static const playbackDivider = Color(0x5758606E);
  static const playbackMuted = Color(0xFFA6B0BE);

  static ThemeData theme() {
    final colorScheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          surface: surface,
        ).copyWith(
          primary: accent,
          secondary: selection,
          surface: surface,
          error: danger,
          onSurface: primaryText,
        );
    return ThemeData(
      brightness: Brightness.dark,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: background,
      fontFamily: 'monospace',
      splashFactory: InkSparkle.splashFactory,
      sliderTheme: const SliderThemeData(
        activeTrackColor: accent,
        inactiveTrackColor: line,
        thumbColor: primaryText,
        overlayColor: Color(0x3340C7CC),
        trackHeight: 2,
      ),
      tooltipTheme: const TooltipThemeData(
        decoration: BoxDecoration(
          color: raisedSurface,
          borderRadius: BorderRadius.all(Radius.circular(6)),
        ),
        textStyle: TextStyle(color: primaryText, fontSize: 11),
      ),
    );
  }
}

class ToolSurface extends StatelessWidget {
  const ToolSurface({
    required this.child,
    super.key,
    this.padding,
    this.radius = 7,
    this.color = AppPalette.surface,
  });

  final Widget child;
  final EdgeInsetsGeometry? padding;
  final double radius;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color,
        border: Border.all(color: AppPalette.line),
        borderRadius: BorderRadius.circular(radius),
      ),
      child: Padding(padding: padding ?? EdgeInsets.zero, child: child),
    );
  }
}
