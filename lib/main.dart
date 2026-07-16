import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'ui/app_palette.dart';
import 'ui/xensynth_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  runApp(const XenSynthApp());
}

class XenSynthApp extends StatelessWidget {
  const XenSynthApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Xen Synth',
      debugShowCheckedModeBanner: false,
      theme: AppPalette.theme(),
      home: const XenSynthScreen(),
    );
  }
}
