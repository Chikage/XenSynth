import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/app/xensynth_controller.dart';
import 'package:xensynth/app/xensynth_settings.dart';
import 'package:xensynth/ui/app_palette.dart';
import 'package:xensynth/ui/hex/hex_keyboard_view.dart';
import 'package:xensynth/ui/spatial/spatial_waterfall_view.dart';
import 'package:xensynth/ui/xensynth_screen.dart';

void main() {
  testWidgets('hexagonal keyboard keeps the app texture background', (
    tester,
  ) async {
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.hexagonal,
      );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );

    expect(
      find.image(const AssetImage('assets/images/waterfall.webp')),
      findsOneWidget,
    );
    expect(find.byType(HexKeyboardView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('spatial mode mounts the 3D waterfall over the app texture', (
    tester,
  ) async {
    final controller = XenSynthController()
      ..settings = const XenSynthSettings(
        layoutMode: KeyboardLayoutMode.spatial,
      );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: XenSynthScreen(controller: controller),
      ),
    );

    expect(
      find.image(const AssetImage('assets/images/waterfall.webp')),
      findsOneWidget,
    );
    expect(find.byType(SpatialWaterfallView), findsOneWidget);
    expect(find.byType(HexKeyboardView), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
