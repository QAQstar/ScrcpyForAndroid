package io.github.miuzarte.scrcpyforandroid.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 系统级悬浮窗（TYPE_APPLICATION_OVERLAY）。
 *
 * 视频画面来源：
 * 直接把 [SurfaceView] 的 [Surface] 交给 [NativeCoreFacade.attachVideoSurface]，
 * 由 PersistentVideoRenderer 通过 EGL 把解码后的帧画到这块 Surface 上。
 * 触控则通过 [Scrcpy.injectTouch] 转发到被控设备。
 *
 * 注意：渲染器同一时刻只持有一个 display surface。
 * 因此本悬浮窗显示时，全屏 Activity 的画面会被“抢走”；
 * 点“全屏”按钮回到 Activity 后，Activity 的 surface 会重新接管。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "floating_window"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "io.github.miuzarte.scrcpyforandroid.action.FLOATING_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FloatingWindowService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    private var root: View? = null
    private var surfaceView: SurfaceView? = null
    private var videoContainer: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var attachedSurface: Surface? = null
    private val activePointers = HashMap<Int, Pair<Int, Int>>()

    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (root == null) {
            showOverlay()
            observeSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        surfaceView = null
        videoContainer = null
        attachedSurface = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // 悬浮窗构建
    // ---------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        density = metrics.density
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val initWidth = (screenWidth * 0.6f).roundToInt()
        val initHeight = (screenHeight * 0.5f).roundToInt()

        // 顶层 FrameLayout：圆角 + 顶栏 + 视频区 + 右下角缩放手柄
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.BLACK)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val topBar = buildTopBar()
        content.addView(topBar)

        val videoWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(Color.BLACK)
        }

        val sv = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(200), dp(320), Gravity.CENTER)
        }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = onSurfaceAvailable(holder.surface)

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = onSurfaceAvailable(holder.surface)

            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()
        })
        sv.setOnTouchListener { _, event ->
            handleSurfaceTouch(event)
            true
        }
        videoWrap.addView(sv)
        videoWrap.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        surfaceView = sv
        videoContainer = videoWrap
        content.addView(videoWrap)

        container.addView(content)

        val resizeHandle = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x66FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(28)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                setMargins(0, 0, dp(6), dp(6))
            }
        }
        setResizeHandle(resizeHandle)
        container.addView(resizeHandle)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            initWidth,
            initHeight,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - initWidth) / 2
            y = (screenHeight - initHeight) / 2
        }
        params = lp
        root = container
        runCatching { windowManager.addView(container, lp) }
            .onFailure { Log.e(TAG, "addView failed", it) }
    }

    private fun buildTopBar(): View {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC222222.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40),
            )
        }
        setDragHandle(topBar)

        topBar.addView(makeBarButton("返回") { injectKey(4) })
        topBar.addView(makeBarButton("主页") { injectKey(3) })
        topBar.addView(makeBarButton("全屏") { openFullscreen() })
        // 空白区域用于拖动
        topBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        topBar.addView(makeBarButton("关闭") { stopSelf() })
        return topBar
    }

    private fun makeBarButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------------
    // 拖动 / 缩放
    // ---------------------------------------------------------------------

    private fun setDragHandle(handle: View) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    lp.x = (startX + dx).coerceIn(-lp.width / 2, screenWidth - lp.width / 2)
                    lp.y = (startY + dy).coerceIn(0, screenHeight - lp.height / 2)
                    runCatching { windowManager.updateViewLayout(root, lp) }
                }
            }
            true
        }
    }

    private fun setResizeHandle(handle: View) {
        var startW = 0
        var startH = 0
        var downRawX = 0f
        var downRawY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = lp.width
                    startH = lp.height
                    downRawX = event.rawX
                    downRawY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.width = (startW + (event.rawX - downRawX)).roundToInt()
                        .coerceIn(dp(200), screenWidth)
                    lp.height = (startH + (event.rawY - downRawY)).roundToInt()
                        .coerceIn(dp(200), screenHeight)
                    runCatching { windowManager.updateViewLayout(root, lp) }
                }

                MotionEvent.ACTION_UP -> fitVideo()
            }
            true
        }
    }

    // ---------------------------------------------------------------------
    // 视频绑定
    // ---------------------------------------------------------------------

    private fun onSurfaceAvailable(surface: Surface) {
        if (!surface.isValid) return
        attachedSurface = surface
        val info = AppRuntime.scrcpy?.currentSessionState?.value ?: return
        scope.launch {
            runCatching { NativeCoreFacade.attachVideoSurface(surface) }
                .onFailure { Log.w(TAG, "attachVideoSurface failed", it) }
        }
        if (info.width > 0 && info.height > 0) {
            runCatching { surfaceView?.holder?.setFixedSize(info.width, info.height) }
        }
        fitVideo(info)
    }

    private fun onSurfaceGone() {
        val surface = attachedSurface ?: return
        attachedSurface = null
        scope.launch { runCatching { NativeCoreFacade.detachVideoSurface(surface) } }
    }

    private fun observeSession() {
        scope.launch {
            val scrcpy = AppRuntime.scrcpy ?: return@launch
            scrcpy.currentSessionState.collect { info ->
                val surface = attachedSurface
                if (info != null && surface != null && surface.isValid) {
                    runCatching { NativeCoreFacade.attachVideoSurface(surface) }
                }
                fitVideo(info)
            }
        }
    }

    /** 在窗口内按视频宽高比居中放置画面，避免拉伸变形。 */
    private fun fitVideo(
        info: Scrcpy.Session.SessionInfo? = AppRuntime.scrcpy?.currentSessionState?.value,
    ) {
        val sv = surfaceView ?: return
        val wrap = videoContainer ?: return
        val wrapW = wrap.width
        val wrapH = wrap.height
        if (wrapW <= 0 || wrapH <= 0) return

        val videoW = info?.width ?: 0
        val videoH = info?.height ?: 0
        val target = if (videoW > 0 && videoH > 0) {
            val aspect = videoW.toFloat() / videoH
            var w = wrapW
            var h = (wrapW / aspect).roundToInt()
            if (h > wrapH) {
                h = wrapH
                w = (wrapH * aspect).roundToInt()
            }
            w to h
        } else {
            wrapW to wrapH
        }

        val lp = sv.layoutParams as FrameLayout.LayoutParams
        if (lp.width != target.first || lp.height != target.second) {
            lp.width = target.first
            lp.height = target.second
            sv.layoutParams = lp
        }
        if (videoW > 0 && videoH > 0) {
            runCatching { sv.holder.setFixedSize(videoW, videoH) }
        }
    }

    // ---------------------------------------------------------------------
    // 触控转发（多指）
    // ---------------------------------------------------------------------

    private fun handleSurfaceTouch(event: MotionEvent) {
        val scrcpy = AppRuntime.scrcpy ?: return
        val sv = surfaceView ?: return
        val info = scrcpy.currentSessionState.value ?: return
        if (info.width <= 0 || info.height <= 0) return
        if (sv.width <= 0 || sv.height <= 0) return

        fun map(index: Int): Pair<Int, Int> {
            val nx = (event.getX(index) / sv.width).coerceIn(0f, 1f)
            val ny = (event.getY(index) / sv.height).coerceIn(0f, 1f)
            val maxX = (info.width - 1).coerceAtLeast(0)
            val maxY = (info.height - 1).coerceAtLeast(0)
            val x = (nx * maxX).roundToInt().coerceIn(0, maxX)
            val y = (ny * maxY).roundToInt().coerceIn(0, maxY)
            return x to y
        }

        fun inject(action: Int, id: Int, x: Int, y: Int, pressure: Float) {
            scope.launch {
                runCatching {
                    scrcpy.injectTouch(
                        action = action,
                        pointerId = id.toLong(),
                        x = x,
                        y = y,
                        screenWidth = info.width,
                        screenHeight = info.height,
                        pressure = pressure,
                    )
                }
            }
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val (x, y) = map(index)
                activePointers[id] = x to y
                inject(MotionEvent.ACTION_DOWN, id, x, y, event.getPressure(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val (x, y) = map(index)
                    activePointers[id] = x to y
                    inject(MotionEvent.ACTION_MOVE, id, x, y, event.getPressure(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val (x, y) = map(index)
                activePointers.remove(id)
                inject(MotionEvent.ACTION_UP, id, x, y, 0f)
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointers.forEach { (id, pos) ->
                    inject(MotionEvent.ACTION_UP, id, pos.first, pos.second, 0f)
                }
                activePointers.clear()
            }
        }
    }

    private fun injectKey(keycode: Int) {
        val scrcpy = AppRuntime.scrcpy ?: return
        scope.launch {
            runCatching {
                scrcpy.injectKeycode(0, keycode)
                scrcpy.injectKeycode(1, keycode)
            }
        }
    }

    private fun openFullscreen() {
        runCatching {
            startActivity(
                StreamActivity.createIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // 前台服务
    // ---------------------------------------------------------------------

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            StreamActivity.createIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Scrcpy 悬浮窗")
            .setContentText("正在镜像设备")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
