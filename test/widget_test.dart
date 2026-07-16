import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:xensynth/main.dart';
import 'package:xensynth/ui/app_palette.dart';

void main() {
  testWidgets('tool surface renders its child', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppPalette.theme(),
        home: const Scaffold(body: ToolSurface(child: Text('XEN SYNTH'))),
      ),
    );

    expect(find.text('XEN SYNTH'), findsOneWidget);
  });

  testWidgets('main player loads the bundled score in landscape', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(1180, 720));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(const XenSynthApp());
    for (var frame = 0; frame < 12; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byIcon(Icons.folder_open_rounded), findsOneWidget);
    expect(find.text('26 EDO'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
