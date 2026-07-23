package icu.ringona.xensynth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import icu.ringona.xensynth.platform.XenSynthPlatformBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private lateinit var platformBridge: XenSynthPlatformBridge

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        platformBridge = XenSynthPlatformBridge(this)
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        MethodChannel(messenger, METHOD_CHANNEL).also { channel ->
            channel.setMethodCallHandler(platformBridge)
            platformBridge.attachMethodChannel(channel)
        }
        EventChannel(messenger, MIDI_EVENT_CHANNEL).setStreamHandler(platformBridge)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLandscapeWindow()
        if (::platformBridge.isInitialized) platformBridge.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::platformBridge.isInitialized) platformBridge.onHostResume()
    }

    override fun onPause() {
        if (::platformBridge.isInitialized) platformBridge.onHostPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::platformBridge.isInitialized) platformBridge.handleIntent(intent)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::platformBridge.isInitialized && platformBridge.onActivityResult(requestCode, resultCode, data)) {
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (::platformBridge.isInitialized &&
            platformBridge.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        if (::platformBridge.isInitialized) platformBridge.close()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureLandscapeWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private companion object {
        const val METHOD_CHANNEL = "icu.ringona.xensynth/platform"
        const val MIDI_EVENT_CHANNEL = "icu.ringona.xensynth/platform/midi"
    }
}
