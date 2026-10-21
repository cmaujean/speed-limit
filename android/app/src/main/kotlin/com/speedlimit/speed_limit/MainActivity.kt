package com.speedlimit.speed_limit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val METHOD_CHANNEL = "com.speedlimit/overlay"
    private val OVERLAY_PERMISSION_REQUEST = 1234

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

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
                "getOverlayState" -> {
                    result.success(
                        mapOf(
                            "isRunning" to OverlayService.isRunning,
                            "hasOverlayPermission" to Settings.canDrawOverlays(this),
                            "overLimitAllowance" to OverlayService.getSavedAllowance(this),
                            "useMetric" to OverlayService.getSavedUseMetric(this),
                            "signStyle" to OverlayService.getSavedSignStyle(this),
                            "currentSpeedLimit" to OverlayService.currentSpeedLimitSnapshot
                        )
                    )
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
                    if (Settings.canDrawOverlays(this) && hasLocationPermission(this)) {
                        val speedLimit = call.argument<Int>("speedLimit") ?: 0
                        val allowance = call.argument<Int>("overLimitAllowance")
                            ?: OverlayService.getSavedAllowance(this)
                        val useMetric = call.argument<Boolean>("useMetric")
                            ?: OverlayService.getSavedUseMetric(this)
                        val signStyle = call.argument<String>("signStyle")
                            ?: OverlayService.getSavedSignStyle(this)

                        OverlayService.savePreferences(
                            context = this,
                            overLimitAllowance = allowance,
                            useMetric = useMetric,
                            signStyle = signStyle
                        )

                        val intent = Intent(this, OverlayService::class.java).apply {
                            putExtra("speedLimit", speedLimit)
                            putExtra("overLimitAllowance", allowance)
                            putExtra("useMetric", useMetric)
                            putExtra("signStyle", signStyle)
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
                    if (!OverlayService.isRunning) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    val speedLimit = call.argument<Int>("speedLimit") ?: 0
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = "UPDATE"
                        putExtra("speedLimit", speedLimit)
                    }
                    startService(intent)
                    result.success(true)
                }
                "updateAllowance" -> {
                    val allowance = call.argument<Int>("overLimitAllowance")
                        ?: OverlayService.getSavedAllowance(this)
                    OverlayService.savePreferences(
                        context = this,
                        overLimitAllowance = allowance
                    )
                    if (OverlayService.isRunning) {
                        val intent = Intent(this, OverlayService::class.java).apply {
                            action = "UPDATE_SETTINGS"
                            putExtra("overLimitAllowance", allowance)
                        }
                        startService(intent)
                    }
                    result.success(true)
                }
                "updateSettings" -> {
                    val allowance = call.argument<Int>("overLimitAllowance")
                        ?: OverlayService.getSavedAllowance(this)
                    val useMetric = call.argument<Boolean>("useMetric")
                        ?: OverlayService.getSavedUseMetric(this)
                    val signStyle = call.argument<String>("signStyle")
                        ?: OverlayService.getSavedSignStyle(this)

                    OverlayService.savePreferences(
                        context = this,
                        overLimitAllowance = allowance,
                        useMetric = useMetric,
                        signStyle = signStyle
                    )
                    if (OverlayService.isRunning) {
                        val intent = Intent(this, OverlayService::class.java).apply {
                            action = "UPDATE_SETTINGS"
                            putExtra("overLimitAllowance", allowance)
                            putExtra("useMetric", useMetric)
                            putExtra("signStyle", signStyle)
                        }
                        startService(intent)
                    }
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
