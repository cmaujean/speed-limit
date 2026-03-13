package com.speedlimit.speed_limit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import com.google.android.gms.location.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.*
import org.json.JSONObject
import org.json.JSONArray

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null
    private var currentSpeedLimit: Int = 0
    private var currentSpeedMph: Float = 0f
    private var overLimitAllowance: Int = 5
    private var isFlashingRed: Boolean = false
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private val NOTIFICATION_ID = 9999
    private val CHANNEL_ID = "speed_limit_overlay"

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Road cache: bulk-fetched roads with geometry ---
    private data class LatLon(val lat: Double, val lon: Double)
    private data class CachedRoad(
        val speedLimit: Int,
        val segments: List<Pair<LatLon, LatLon>>,  // line segments making up the road
        val bearing: Double?  // overall bearing of the way, if computable
    )

    private var cachedRoads: List<CachedRoad> = emptyList()
    private var cacheCenterLat: Double = 0.0
    private var cacheCenterLon: Double = 0.0
    private val CACHE_RADIUS_M = 500.0       // fetch roads within 500m
    private val REFETCH_THRESHOLD_M = 150.0  // re-fetch when within 150m of edge
    private var isFetching: Boolean = false

    // Stall detection
    private var lastLocationTime: Long = 0
    private var stallCheckHandler: Handler? = null
    private var stallCheckRunnable: Runnable? = null
    private val STALL_TIMEOUT_MS = 10_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        acquireWakeLock()
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speedLimit = intent?.getIntExtra("speedLimit", 0) ?: 0
        currentSpeedLimit = speedLimit
        overLimitAllowance = intent?.getIntExtra("overLimitAllowance", overLimitAllowance) ?: overLimitAllowance

        if (intent?.action == "UPDATE_ALLOWANCE") {
            overLimitAllowance = intent.getIntExtra("overLimitAllowance", overLimitAllowance)
            checkOverSpeed()
            return START_STICKY
        }

        if (intent?.action == "UPDATE") {
            updateBubble()
            return START_STICKY
        }

        if (overlayView != null) {
            updateBubble()
            return START_STICKY
        }

        val sizePx = dpToPx(64f).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16f).toInt()
            y = dpToPx(100f).toInt()
        }

        overlayView = ImageView(this).apply {
            setImageDrawable(SpeedLimitDrawable(currentSpeedLimit))
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
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
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

    // ---- Core location handler ----

    private fun onNewLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val bearing = if (location.hasBearing()) location.bearing.toDouble() else null

        // Update speed on every GPS tick
        currentSpeedMph = location.speed * 2.23694f
        checkOverSpeed()

        // Local match against cached roads (instant, no network)
        if (cachedRoads.isNotEmpty()) {
            val matched = matchNearestRoad(lat, lon, bearing)
            if (matched != null && matched != currentSpeedLimit) {
                currentSpeedLimit = matched
                checkOverSpeed()
                updateBubble()
                updateNotification(matched)
            }
        }

        // Check if we need to re-fetch: either no cache yet, or approaching edge
        val distFromCenter = distanceMeters(lat, lon, cacheCenterLat, cacheCenterLon)
        val needRefetch = cachedRoads.isEmpty() || distFromCenter > (CACHE_RADIUS_M - REFETCH_THRESHOLD_M)

        if (needRefetch && !isFetching) {
            isFetching = true
            val fetchLat = lat
            val fetchLon = lon
            thread {
                val roads = fetchRoadsInRadius(fetchLat, fetchLon, CACHE_RADIUS_M)
                if (roads != null) {
                    cachedRoads = roads
                    cacheCenterLat = fetchLat
                    cacheCenterLon = fetchLon
                    Log.i("SpeedLimit", "Cached ${roads.size} roads around ($fetchLat, $fetchLon)")

                    // Re-match immediately with fresh data
                    val matched = matchNearestRoad(lat, lon, bearing)
                    val newLimit = matched ?: 0
                    if (newLimit != currentSpeedLimit) {
                        currentSpeedLimit = newLimit
                        Handler(Looper.getMainLooper()).post {
                            checkOverSpeed()
                            updateBubble()
                            if (newLimit > 0) updateNotification(newLimit)
                        }
                    }
                }
                isFetching = false
            }
        }
    }

    // ---- Local road matching (no network) ----

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
            if (minDist < 50.0) { // only consider roads within 50m
                candidates.add(Candidate(road, minDist, closestSegBearing))
            }
        }

        if (candidates.isEmpty()) return null

        // If we have a GPS bearing, score candidates by distance + bearing match
        if (bearingDeg != null) {
            val scored = candidates.map { c ->
                val bearingPenalty = if (c.segBearing != null) {
                    // Angle difference 0-180, normalized to 0-1
                    // Roads are bidirectional, so use min of forward and reverse
                    val diff = angleDiff(bearingDeg, c.segBearing)
                    val reverseDiff = angleDiff(bearingDeg, (c.segBearing + 180) % 360)
                    minOf(diff, reverseDiff) / 180.0
                } else 0.0
                // Combined score: distance (meters) + bearing penalty * 30m weight
                val score = c.distance + bearingPenalty * 30.0
                Pair(c, score)
            }
            val best = scored.minByOrNull { it.second }
            return best?.first?.road?.speedLimit
        }

        // No bearing: just pick closest
        val closest = candidates.minByOrNull { it.distance }
        return closest?.road?.speedLimit
    }

    // ---- Geometry utilities ----

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    /** Distance in meters from point (plat, plon) to line segment (a -> b) */
    private fun pointToSegmentDistance(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double
    ): Double {
        // Project onto segment in local meter space
        val ax = (alon - plon) * cos(Math.toRadians(plat)) * 111320
        val ay = (alat - plat) * 110540
        val bx = (blon - plon) * cos(Math.toRadians(plat)) * 111320
        val by = (blat - plat) * 110540

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.001) {
            // Degenerate segment
            return sqrt(ax * ax + ay * ay)
        }

        val t = ((0 - ax) * dx + (0 - ay) * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)
        val projX = ax + tc * dx
        val projY = ay + tc * dy
        return sqrt(projX * projX + projY * projY)
    }

    /** Bearing in degrees from point a to point b */
    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Absolute angle difference in degrees (0-180) */
    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    // ---- Overpass API: bulk fetch with geometry ----

    private fun fetchRoadsInRadius(lat: Double, lon: Double, radiusM: Double): List<CachedRoad>? {
        try {
            val query = "[out:json][timeout:10];way(around:${radiusM.toInt()},$lat,$lon)[\"highway\"][\"maxspeed\"];out body geom;"
            val url = URL("https://overpass-api.de/api/interpreter")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = "data=${URLEncoder.encode(query, "UTF-8")}"
            conn.outputStream.use { it.write(postData.toByteArray()) }

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
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
                val limit = parseSpeedLimit(maxspeed) ?: continue

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
                    val first = segments.first().first
                    val last = segments.last().second
                    val roadBearing = bearing(first.lat, first.lon, last.lat, last.lon)
                    roads.add(CachedRoad(limit, segments, roadBearing))
                }
            }
            return roads
        } catch (e: Exception) {
            Log.w("SpeedLimit", "Failed to fetch roads", e)
            return null
        }
    }

    private fun parseSpeedLimit(raw: String): Int? {
        val trimmed = raw.trim().lowercase()
        if (trimmed in listOf("none", "signals", "walk")) return null
        val match = Regex("(\\d+)").find(trimmed)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ---- Speed alert ----

    private fun checkOverSpeed() {
        val isOver = currentSpeedLimit > 0 && currentSpeedMph > currentSpeedLimit + overLimitAllowance
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
                overlayView?.setImageDrawable(SpeedLimitDrawable(currentSpeedLimit, alerting = showRed))
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
            overlayView?.setImageDrawable(SpeedLimitDrawable(currentSpeedLimit, alerting = false))
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

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Speed Limit: $speedLimit mph")
                .setContentText("Tracking active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Speed Limit: $speedLimit mph")
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
        stopFlashing()
        stopStallDetection()
        stopLocationUpdates()
        fusedLocationClient = null
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
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

    inner class SpeedLimitDrawable(private val speedLimit: Int, private val alerting: Boolean = false) : Drawable() {
        private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D32F2F")
            style = Paint.Style.STROKE
            strokeWidth = 8f
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
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = Math.min(bounds.width(), bounds.height()) / 2f - 6f

            canvas.drawCircle(cx + 2f, cy + 2f, radius, shadowPaint)

            if (alerting) {
                canvas.drawCircle(cx, cy, radius, alertFillPaint)
                if (speedLimit > 0) {
                    val text = speedLimit.toString()
                    alertTextPaint.textSize = radius * (if (text.length <= 2) 1.0f else 0.75f)
                    val textY = cy - (alertTextPaint.descent() + alertTextPaint.ascent()) / 2
                    canvas.drawText(text, cx, textY, alertTextPaint)
                }
            } else {
                canvas.drawCircle(cx, cy, radius, whitePaint)
                redPaint.strokeWidth = radius * 0.12f
                canvas.drawCircle(cx, cy, radius - redPaint.strokeWidth / 2, redPaint)

                if (speedLimit > 0) {
                    val text = speedLimit.toString()
                    textPaint.textSize = radius * (if (text.length <= 2) 1.0f else 0.75f)
                    val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
                    canvas.drawText(text, cx, textY, textPaint)
                } else {
                    unknownPaint.textSize = radius * 0.9f
                    val textY = cy - (unknownPaint.descent() + unknownPaint.ascent()) / 2
                    canvas.drawText("--", cx, textY, unknownPaint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
