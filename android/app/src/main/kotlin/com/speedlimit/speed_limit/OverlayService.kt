package com.speedlimit.speed_limit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONObject

class OverlayService : Service() {

    companion object {
        private const val PREFS_NAME = "speed_limit_prefs"
        private const val KEY_ALLOWANCE = "over_limit_allowance"
        private const val KEY_USE_METRIC = "use_metric"
        private const val KEY_SIGN_STYLE = "sign_style"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentSpeedLimitSnapshot: Int = 0
            private set

        fun getSavedAllowance(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_ALLOWANCE, 5).coerceIn(0, 50)
        }

        fun getSavedUseMetric(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_USE_METRIC, false)
        }

        fun getSavedSignStyle(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_SIGN_STYLE, "circle") ?: "circle"
            return if (raw == "us") "us" else "circle"
        }

        fun savePreferences(
            context: Context,
            overLimitAllowance: Int? = null,
            useMetric: Boolean? = null,
            signStyle: String? = null,
            bubbleX: Int? = null,
            bubbleY: Int? = null
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (overLimitAllowance != null) putInt(KEY_ALLOWANCE, overLimitAllowance.coerceIn(0, 50))
                if (useMetric != null) putBoolean(KEY_USE_METRIC, useMetric)
                if (signStyle != null) putString(KEY_SIGN_STYLE, if (signStyle == "us") "us" else "circle")
                if (bubbleX != null) putInt(KEY_BUBBLE_X, bubbleX)
                if (bubbleY != null) putInt(KEY_BUBBLE_Y, bubbleY)
                apply()
            }
        }

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var currentSpeedLimit: Int = 0
    private var currentSpeedMph: Float = 0f
    private var currentSpeedKmh: Float = 0f
    private var overLimitAllowance: Int = 5
    private var useMetric: Boolean = false
    private var signStyle: String = "circle"

    private var isFlashingRed: Boolean = false
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private val NOTIFICATION_ID = 9999
    private val CHANNEL_ID = "speed_limit_overlay"

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Road cache: bulk-fetched roads with geometry (accessed on mainHandler) ---
    private enum class SpeedUnit { MPH, KMH, IMPLICIT }
    private enum class Oneway { BIDIRECTIONAL, FORWARD, REVERSE }
    private data class ParsedSpeed(val value: Int, val unit: SpeedUnit) {
        fun toDisplayUnit(metric: Boolean): Int {
            if (value <= 0) return 0
            return if (metric) {
                when (unit) {
                    SpeedUnit.KMH, SpeedUnit.IMPLICIT -> value
                    SpeedUnit.MPH -> maxOf(5, ((value * 1.60934) / 5.0).roundToInt() * 5)
                }
            } else {
                when (unit) {
                    SpeedUnit.MPH, SpeedUnit.IMPLICIT -> value
                    SpeedUnit.KMH -> maxOf(5, ((value * 0.621371) / 5.0).roundToInt() * 5)
                }
            }
        }
    }

    private data class LatLon(val lat: Double, val lon: Double)
    private data class CachedRoad(
        val parsedSpeed: ParsedSpeed,
        val segments: List<Pair<LatLon, LatLon>>,
        val oneway: Oneway
    )

    private var cachedRoads: List<CachedRoad> = emptyList()
    private var hasValidCache: Boolean = false
    private var cacheCenterLat: Double = 0.0
    private var cacheCenterLon: Double = 0.0
    private val CACHE_RADIUS_M = 500.0
    private val REFETCH_THRESHOLD_M = 150.0
    private var isFetching: Boolean = false
    private var consecutiveFailures: Int = 0
    private var nextAllowedFetchTimeMs: Long = 0L
    private var consecutiveUnmatchedTicks: Int = 0
    private var lastLocation: Location? = null
    private var lastValidBearing: Double? = null

    private val OVERPASS_ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )
    private val USER_AGENT = "SpeedLimit/1.1 (+https://github.com/cmaujean/speed-limit)"

    // Stall detection
    private var lastLocationTime: Long = 0
    private var stallCheckHandler: Handler? = null
    private var stallCheckRunnable: Runnable? = null
    private val STALL_TIMEOUT_MS = 10_000L
    private val MIN_BEARING_SPEED_MPS = 1.5f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overLimitAllowance = getSavedAllowance(this)
        useMetric = getSavedUseMetric(this)
        signStyle = getSavedSignStyle(this)

        if (!Settings.canDrawOverlays(this) || !hasLocationPermission(this)) {
            Log.w("SpeedLimit", "Missing overlay or location permission on service start; stopping self")
            isRunning = false
            stopSelf()
            return
        }

        isRunning = true
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        acquireWakeLock()
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning || !Settings.canDrawOverlays(this) || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null) {
            if (intent.hasExtra("overLimitAllowance")) {
                overLimitAllowance = intent.getIntExtra("overLimitAllowance", overLimitAllowance).coerceIn(0, 50)
            }
            if (intent.hasExtra("useMetric")) {
                useMetric = intent.getBooleanExtra("useMetric", useMetric)
            }
            if (intent.hasExtra("signStyle")) {
                val style = intent.getStringExtra("signStyle")
                if (style != null) signStyle = if (style == "us") "us" else "circle"
            }
            savePreferences(
                context = this,
                overLimitAllowance = overLimitAllowance,
                useMetric = useMetric,
                signStyle = signStyle
            )
        } else {
            overLimitAllowance = getSavedAllowance(this)
            useMetric = getSavedUseMetric(this)
            signStyle = getSavedSignStyle(this)
        }

        if (intent?.action == "UPDATE_ALLOWANCE" || intent?.action == "UPDATE_SETTINGS") {
            recomputeCurrentLimitForUnit()
            updateOverlayDimensionsIfNeeded()
            checkOverSpeed()
            updateBubble()
            updateNotification(currentSpeedLimit)
            if (overlayView != null) {
                return START_STICKY
            }
        }

        if (intent?.action == "UPDATE") {
            if (intent.hasExtra("speedLimit")) {
                currentSpeedLimit = intent.getIntExtra("speedLimit", 0)
                currentSpeedLimitSnapshot = currentSpeedLimit
            }
            checkOverSpeed()
            updateBubble()
            if (overlayView != null) {
                return START_STICKY
            }
        }

        if (intent?.hasExtra("speedLimit") == true && overlayView == null) {
            currentSpeedLimit = intent.getIntExtra("speedLimit", 0)
            currentSpeedLimitSnapshot = currentSpeedLimit
        }

        if (overlayView != null) {
            updateOverlayDimensionsIfNeeded()
            checkOverSpeed()
            updateBubble()
            return START_STICKY
        }

        val (widthPx, heightPx) = getBubbleSizePx()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(KEY_BUBBLE_X, -1)
        val savedY = prefs.getInt(KEY_BUBBLE_Y, -1)
        val defaultX = dpToPx(16f).toInt()
        val defaultY = dpToPx(100f).toInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(if (savedX >= 0) savedX else defaultX, widthPx)
            y = clampY(if (savedY >= 0) savedY else defaultY, heightPx)
        }
        overlayParams = params

        overlayView = ImageView(this).apply {
            setImageDrawable(SpeedLimitDrawable(currentSpeedLimit, alerting = false, style = signStyle))
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 25) moved = true
                    params.x = clampX(initialX + dx.toInt(), params.width)
                    params.y = clampY(initialY + dy.toInt(), params.height)
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePreferences(this, bubbleX = params.x, bubbleY = params.y)
                    } else {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        if (launchIntent != null) startActivity(launchIntent)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(overlayView, params)
        startLocationUpdates()
        startStallDetection()

        return START_STICKY
    }

    private fun getBubbleSizePx(): Pair<Int, Int> {
        return if (signStyle == "us") {
            Pair(dpToPx(64f).toInt(), dpToPx(80f).toInt())
        } else {
            val s = dpToPx(64f).toInt()
            Pair(s, s)
        }
    }

    private fun updateOverlayDimensionsIfNeeded() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val (w, h) = getBubbleSizePx()
        if (params.width != w || params.height != h) {
            params.width = w
            params.height = h
            params.x = clampX(params.x, w)
            params.y = clampY(params.y, h)
            windowManager?.updateViewLayout(view, params)
        }
    }

    private fun clampX(x: Int, widthPx: Int): Int {
        val screenW = resources.displayMetrics.widthPixels
        val maxX = maxOf(0, screenW - widthPx)
        return x.coerceIn(0, maxX)
    }

    private fun clampY(y: Int, heightPx: Int): Int {
        val screenH = resources.displayMetrics.heightPixels
        val maxY = maxOf(0, screenH - heightPx)
        return y.coerceIn(0, maxY)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedLimit::LocationWakeLock"
        ).apply { acquire() }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        stopLocationUpdates()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocationTime = System.currentTimeMillis()
                onNewLocation(location)
            }
        }

        fusedLocationClient?.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )

        lastLocationTime = System.currentTimeMillis()
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun startStallDetection() {
        stopStallDetection()
        stallCheckHandler = Handler(Looper.getMainLooper())
        stallCheckRunnable = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                val elapsed = System.currentTimeMillis() - lastLocationTime
                if (elapsed > STALL_TIMEOUT_MS) {
                    Log.w("SpeedLimit", "Location stalled for ${elapsed}ms, re-registering")
                    startLocationUpdates()
                }
                stallCheckHandler?.postDelayed(this, STALL_TIMEOUT_MS)
            }
        }
        stallCheckHandler?.postDelayed(stallCheckRunnable!!, STALL_TIMEOUT_MS)
    }

    private fun stopStallDetection() {
        stallCheckRunnable?.let { stallCheckHandler?.removeCallbacks(it) }
        stallCheckHandler = null
        stallCheckRunnable = null
    }

    private fun recomputeCurrentLimitForUnit() {
        val loc = lastLocation ?: return
        if (!hasValidCache) return
        val matched = matchNearestRoad(loc.latitude, loc.longitude, lastValidBearing)
        if (matched != null) {
            currentSpeedLimit = matched
            currentSpeedLimitSnapshot = matched
        }
    }

    // ---- Core location handler (runs on main looper) ----

    private fun onNewLocation(location: Location) {
        lastLocation = location
        val lat = location.latitude
        val lon = location.longitude
        if (location.hasBearing() && location.speed >= MIN_BEARING_SPEED_MPS) {
            lastValidBearing = location.bearing.toDouble()
        }
        val bearing = lastValidBearing

        currentSpeedMph = location.speed * 2.23694f
        currentSpeedKmh = location.speed * 3.6f
        checkOverSpeed()

        if (hasValidCache) {
            val matched = matchNearestRoad(lat, lon, bearing)
            if (matched != null) {
                consecutiveUnmatchedTicks = 0
                if (matched != currentSpeedLimit) {
                    currentSpeedLimit = matched
                    currentSpeedLimitSnapshot = matched
                    checkOverSpeed()
                    updateBubble()
                    updateNotification(matched)
                }
            } else {
                consecutiveUnmatchedTicks++
                if (consecutiveUnmatchedTicks >= 3 && currentSpeedLimit != 0) {
                    currentSpeedLimit = 0
                    currentSpeedLimitSnapshot = 0
                    checkOverSpeed()
                    updateBubble()
                    updateNotification(0)
                }
            }
        }

        val distFromCenter = if (hasValidCache) {
            distanceMeters(lat, lon, cacheCenterLat, cacheCenterLon)
        } else {
            Double.MAX_VALUE
        }
        val needRefetch = !hasValidCache || distFromCenter > (CACHE_RADIUS_M - REFETCH_THRESHOLD_M)
        val now = System.currentTimeMillis()

        if (needRefetch && !isFetching && now >= nextAllowedFetchTimeMs) {
            isFetching = true
            val fetchLat = lat
            val fetchLon = lon
            thread {
                val roads = fetchRoadsInRadius(fetchLat, fetchLon, CACHE_RADIUS_M)
                mainHandler.post {
                    isFetching = false
                    if (!isRunning) return@post

                    if (roads != null) {
                        cachedRoads = roads
                        hasValidCache = true
                        cacheCenterLat = fetchLat
                        cacheCenterLon = fetchLon
                        consecutiveFailures = 0
                        nextAllowedFetchTimeMs = 0L
                        Log.i("SpeedLimit", "Cached ${roads.size} roads around ($fetchLat, $fetchLon)")

                        val latestLoc = lastLocation
                        val matchLat = latestLoc?.latitude ?: fetchLat
                        val matchLon = latestLoc?.longitude ?: fetchLon
                        val matched = matchNearestRoad(matchLat, matchLon, lastValidBearing)
                        val newLimit = matched ?: 0
                        if (matched != null) {
                            consecutiveUnmatchedTicks = 0
                        }
                        if (newLimit != currentSpeedLimit) {
                            currentSpeedLimit = newLimit
                            currentSpeedLimitSnapshot = newLimit
                            checkOverSpeed()
                            updateBubble()
                            updateNotification(newLimit)
                        }
                    } else {
                        consecutiveFailures++
                        val shift = minOf(consecutiveFailures - 1, 4)
                        val backoffMs = minOf(60_000L, 5_000L * (1L shl shift))
                        nextAllowedFetchTimeMs = System.currentTimeMillis() + backoffMs
                        Log.w("SpeedLimit", "Overpass fetch failed; backing off for ${backoffMs}ms")
                    }
                }
            }
        }
    }

    // ---- Local road matching (no network, runs on main looper) ----

    private fun matchNearestRoad(lat: Double, lon: Double, bearingDeg: Double?): Int? {
        if (cachedRoads.isEmpty()) return null

        data class Candidate(val road: CachedRoad, val distance: Double, val segBearing: Double?)

        val candidates = mutableListOf<Candidate>()

        for (road in cachedRoads) {
            var minDist = Double.MAX_VALUE
            var closestSegBearing: Double? = null
            for ((a, b) in road.segments) {
                val dist = pointToSegmentDistance(lat, lon, a.lat, a.lon, b.lat, b.lon)
                if (dist < minDist) {
                    minDist = dist
                    closestSegBearing = bearing(a.lat, a.lon, b.lat, b.lon)
                }
            }
            if (minDist < 50.0) {
                candidates.add(Candidate(road, minDist, closestSegBearing))
            }
        }

        if (candidates.isEmpty()) return null

        if (bearingDeg != null) {
            val scored = candidates.map { c ->
                val bearingPenalty = if (c.segBearing != null) {
                    val diff = when (c.road.oneway) {
                        Oneway.FORWARD -> angleDiff(bearingDeg, c.segBearing)
                        Oneway.REVERSE -> angleDiff(bearingDeg, (c.segBearing + 180.0) % 360.0)
                        Oneway.BIDIRECTIONAL -> {
                            val forwardDiff = angleDiff(bearingDeg, c.segBearing)
                            val reverseDiff = angleDiff(bearingDeg, (c.segBearing + 180.0) % 360.0)
                            minOf(forwardDiff, reverseDiff)
                        }
                    }
                    diff / 180.0
                } else 0.0
                val score = c.distance + bearingPenalty * 30.0
                Pair(c, score)
            }
            val best = scored.minByOrNull { it.second }
            return best?.first?.road?.parsedSpeed?.toDisplayUnit(useMetric)
        }

        val closest = candidates.minByOrNull { it.distance }
        return closest?.road?.parsedSpeed?.toDisplayUnit(useMetric)
    }

    // ---- Geometry utilities ----

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun pointToSegmentDistance(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double
    ): Double {
        val ax = (alon - plon) * cos(Math.toRadians(plat)) * 111320
        val ay = (alat - plat) * 110540
        val bx = (blon - plon) * cos(Math.toRadians(plat)) * 111320
        val by = (blat - plat) * 110540

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.001) {
            return sqrt(ax * ax + ay * ay)
        }

        val t = ((0 - ax) * dx + (0 - ay) * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)
        val projX = ax + tc * dx
        val projY = ay + tc * dy
        return sqrt(projX * projX + projY * projY)
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    // ---- Overpass API: bulk fetch with geometry & mirror fallback ----

    private fun fetchRoadsInRadius(lat: Double, lon: Double, radiusM: Double): List<CachedRoad>? {
        val query = "[out:json][timeout:10];way(around:${radiusM.toInt()},$lat,$lon)[\"highway\"][\"maxspeed\"];out body geom;"
        val postData = "data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()

        for (endpoint in OVERPASS_ENDPOINTS) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", USER_AGENT)
                }

                conn.outputStream.use { it.write(postData) }

                if (conn.responseCode != 200) {
                    Log.w("SpeedLimit", "Overpass endpoint $endpoint returned HTTP ${conn.responseCode}")
                    conn.disconnect()
                    continue
                }

                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()

                val json = JSONObject(body)
                val elements = json.getJSONArray("elements")
                val roads = mutableListOf<CachedRoad>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val tags = el.optJSONObject("tags") ?: continue
                    val maxspeed = tags.optString("maxspeed", "")
                    if (maxspeed.isEmpty()) continue
                    val parsedSpeed = parseSpeedLimit(maxspeed) ?: continue
                    val oneway = parseOneway(tags.optString("oneway", ""))

                    val geometry = el.optJSONArray("geometry") ?: continue
                    val segments = mutableListOf<Pair<LatLon, LatLon>>()
                    for (j in 0 until geometry.length() - 1) {
                        val p1 = geometry.getJSONObject(j)
                        val p2 = geometry.getJSONObject(j + 1)
                        segments.add(
                            Pair(
                                LatLon(p1.getDouble("lat"), p1.getDouble("lon")),
                                LatLon(p2.getDouble("lat"), p2.getDouble("lon"))
                            )
                        )
                    }

                    if (segments.isNotEmpty()) {
                        roads.add(CachedRoad(parsedSpeed, segments, oneway))
                    }
                }
                return roads
            } catch (e: Exception) {
                Log.w("SpeedLimit", "Failed to fetch roads from $endpoint", e)
                conn?.disconnect()
            }
        }
        return null
    }

    private fun parseSpeedLimit(raw: String): ParsedSpeed? {
        val trimmed = raw.trim().lowercase()
        if (trimmed in listOf("none", "signals", "walk", "variable")) return null
        val match = Regex("(\\d+)").find(trimmed) ?: return null
        val value = match.groupValues[1].toIntOrNull() ?: return null
        if (value <= 0) return null

        val unit = when {
            trimmed.contains("mph") -> SpeedUnit.MPH
            trimmed.contains("km/h") || trimmed.contains("kph") || trimmed.contains("kmh") -> SpeedUnit.KMH
            else -> SpeedUnit.IMPLICIT
        }
        return ParsedSpeed(value, unit)
    }

    private fun parseOneway(raw: String): Oneway {
        return when (raw.trim().lowercase()) {
            "yes", "true", "1" -> Oneway.FORWARD
            "-1", "reverse" -> Oneway.REVERSE
            else -> Oneway.BIDIRECTIONAL
        }
    }

    // ---- Speed alert ----

    private fun currentSpeedInSelectedUnit(): Float {
        return if (useMetric) currentSpeedKmh else currentSpeedMph
    }

    private fun unitLabel(): String {
        return if (useMetric) "km/h" else "mph"
    }

    private fun checkOverSpeed() {
        val speed = currentSpeedInSelectedUnit()
        val isOver = currentSpeedLimit > 0 && speed > currentSpeedLimit + overLimitAllowance
        if (isOver && !isFlashingRed) {
            startFlashing()
        } else if (!isOver && isFlashingRed) {
            stopFlashing()
        }
    }

    private fun startFlashing() {
        isFlashingRed = true
        flashHandler = Handler(Looper.getMainLooper())
        var showRed = true
        flashRunnable = object : Runnable {
            override fun run() {
                overlayView?.setImageDrawable(
                    SpeedLimitDrawable(currentSpeedLimit, alerting = showRed, style = signStyle)
                )
                overlayView?.invalidate()
                showRed = !showRed
                flashHandler?.postDelayed(this, 500)
            }
        }
        flashHandler?.post(flashRunnable!!)
    }

    private fun stopFlashing() {
        isFlashingRed = false
        flashRunnable?.let { flashHandler?.removeCallbacks(it) }
        flashHandler = null
        flashRunnable = null
        updateBubble()
    }

    // ---- Bubble drawing ----

    private fun updateBubble() {
        if (!isFlashingRed) {
            overlayView?.setImageDrawable(
                SpeedLimitDrawable(currentSpeedLimit, alerting = false, style = signStyle)
            )
            overlayView?.invalidate()
        }
    }

    private fun updateNotification(speedLimit: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (speedLimit > 0) {
            "Speed Limit: $speedLimit ${unitLabel()}"
        } else {
            "Speed Limit: --"
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("Tracking active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText("Tracking active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentSpeedLimitSnapshot = 0
        mainHandler.removeCallbacksAndMessages(null)
        stopFlashing()
        stopStallDetection()
        stopLocationUpdates()
        fusedLocationClient = null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayParams = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speed Limit Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows speed limit overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Speed Limit Active")
                .setContentText("Tracking speed limit")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Speed Limit Active")
                .setContentText("Tracking speed limit")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )
    }

    inner class SpeedLimitDrawable(
        private val speedLimit: Int,
        private val alerting: Boolean = false,
        private val style: String = "circle"
    ) : Drawable() {
        private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F")
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val blackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val whiteBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val unknownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#757575")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val alertFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F")
            style = Paint.Style.FILL
        }
        private val alertTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun draw(canvas: Canvas) {
            if (style == "us") {
                drawUsSign(canvas)
            } else {
                drawCircleSign(canvas)
            }
        }

        private fun drawCircleSign(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = minOf(bounds.width(), bounds.height()) / 2f - 6f
            val displayText = if (speedLimit > 0) speedLimit.toString() else "--"

            canvas.drawCircle(cx + 2f, cy + 2f, radius, shadowPaint)

            if (alerting) {
                canvas.drawCircle(cx, cy, radius, alertFillPaint)
                alertTextPaint.textSize = radius * (if (displayText.length <= 2) 1.0f else 0.75f)
                val textY = cy - (alertTextPaint.descent() + alertTextPaint.ascent()) / 2
                canvas.drawText(displayText, cx, textY, alertTextPaint)
            } else {
                canvas.drawCircle(cx, cy, radius, whitePaint)
                redPaint.strokeWidth = radius * 0.14f
                canvas.drawCircle(cx, cy, radius - redPaint.strokeWidth / 2, redPaint)

                val activePaint = if (speedLimit > 0) textPaint else unknownPaint
                activePaint.textSize = radius * (if (displayText.length <= 2) 0.95f else 0.75f)
                val textY = cy - (activePaint.descent() + activePaint.ascent()) / 2
                canvas.drawText(displayText, cx, textY, activePaint)
            }
        }

        private fun drawUsSign(canvas: Canvas) {
            val pad = 4f
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val rect = RectF(pad, pad, w - pad, h - pad)
            val shadowRect = RectF(pad + 2f, pad + 2f, w - pad + 2f, h - pad + 2f)
            val corner = minOf(w, h) * 0.14f
            val cx = bounds.exactCenterX()
            val displayText = if (speedLimit > 0) speedLimit.toString() else "--"

            canvas.drawRoundRect(shadowRect, corner, corner, shadowPaint)

            if (alerting) {
                canvas.drawRoundRect(rect, corner, corner, alertFillPaint)
                whiteBorderPaint.strokeWidth = minOf(w, h) * 0.05f
                val inset = whiteBorderPaint.strokeWidth
                val innerRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                canvas.drawRoundRect(innerRect, corner * 0.75f, corner * 0.75f, whiteBorderPaint)

                alertTextPaint.textSize = w * 0.16f
                canvas.drawText("SPEED", cx, rect.top + h * 0.23f, alertTextPaint)
                canvas.drawText("LIMIT", cx, rect.top + h * 0.37f, alertTextPaint)

                alertTextPaint.textSize = w * (if (displayText.length <= 2) 0.44f else 0.34f)
                val numCenterY = rect.top + h * 0.68f
                val textY = numCenterY - (alertTextPaint.descent() + alertTextPaint.ascent()) / 2
                canvas.drawText(displayText, cx, textY, alertTextPaint)
            } else {
                canvas.drawRoundRect(rect, corner, corner, whitePaint)
                blackBorderPaint.strokeWidth = minOf(w, h) * 0.05f
                val inset = blackBorderPaint.strokeWidth
                val innerRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                canvas.drawRoundRect(innerRect, corner * 0.75f, corner * 0.75f, blackBorderPaint)

                headerPaint.textSize = w * 0.16f
                canvas.drawText("SPEED", cx, rect.top + h * 0.23f, headerPaint)
                canvas.drawText("LIMIT", cx, rect.top + h * 0.37f, headerPaint)

                val activePaint = if (speedLimit > 0) textPaint else unknownPaint
                activePaint.textSize = w * (if (displayText.length <= 2) 0.44f else 0.34f)
                val numCenterY = rect.top + h * 0.68f
                val textY = numCenterY - (activePaint.descent() + activePaint.ascent()) / 2
                canvas.drawText(displayText, cx, textY, activePaint)
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
