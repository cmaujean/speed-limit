package com.speedlimit.speed_limit

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val METHOD_CHANNEL = "com.speedlimit/overlay"
    private val EVENT_CHANNEL = "com.speedlimit/overlay_events"
    private val OVERLAY_PERMISSION_REQUEST = 1234

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkOverlayPermission" -> {
                    result.success(Settings.canDrawOverlays(this))
                }
                "isOverlayRunning" -> {
                    result.success(OverlayService.isRunning)
                }
                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                    }
                    result.success(null)
                }
                "showOverlay" -> {
                    if (Settings.canDrawOverlays(this)) {
                        val speedLimit = call.argument<Int>("speedLimit") ?: 0
                        val allowance = call.argument<Int>("overLimitAllowance") ?: 5
                        val intent = Intent(this, OverlayService::class.java).apply {
                            putExtra("speedLimit", speedLimit)
                            putExtra("overLimitAllowance", allowance)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "updateOverlay" -> {
                    val speedLimit = call.argument<Int>("speedLimit") ?: 0
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "UPDATE"
                        putExtra("speedLimit", speedLimit)
                    }
                    startService(intent)
                    result.success(true)
                }
                "updateAllowance" -> {
                    val allowance = call.argument<Int>("overLimitAllowance") ?: 5
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "UPDATE_ALLOWANCE"
                        putExtra("overLimitAllowance", allowance)
                    }
                    startService(intent)
                    result.success(true)
                }
                "hideOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }
}
