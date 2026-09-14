package io.github.miuzarte.scrcpyforandroid.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.miuzarte.scrcpyforandroid.NativeCoreFacade
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.storage.Storage.appSettings
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonAction
import io.github.miuzarte.scrcpyforandroid.widgets.VirtualButtonActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 系统级悬浮窗（TYPE_APPLICATION_OVERLAY），样式对齐 Easycontrol 车机版：
 * 顶部横条拖动、右下角（透明）手柄按画面长宽比缩放、下方为虚拟按键。
 *
 * 视频画面通过 [NativeCoreFacade.attachVideoSurface] 绑定到内置 [SurfaceView]；
 * 触控经 [Scrcpy.injectTouch] 转发到被控设备。
 *
 * 悬浮窗内容使用 Compose 渲染，以便直接复用项目的 [VirtualButtonAction] 图标与样式。
 */
class FloatingWindowService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "floating_window"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "io.github.miuzarte.scrcpyforandroid.action.FLOATING_STOP"

        private const val DEFAULT_ASPECT = 16f / 9f
        private const val MIN_VIDEO_WIDTH_DP = 160
        private const val BOTTOM_BAR_DP = 44
        private const val CORNER_DP = 14

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

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreInstance = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var windowManager: WindowManager
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attachedSurface: Surface? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var statusBarHeight = 0
    private var density = 1f

    private var session by mutableStateOf<Scrcpy.Session.SessionInfo?>(null)
    private var outsideActions by mutableStateOf<List<VirtualButtonAction>>(emptyList())
    private var moreActions by mutableStateOf<List<VirtualButtonAction>>(emptyList())

    private var videoAspect = DEFAULT_ASPECT

    private val activePointers = HashMap<Int, Pair<Int, Int>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (root == null) {
            showOverlay()
            observeSession()
            observeActions()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        attachedSurface = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 悬浮窗
    // ------------------------------------------------------------------

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        density = metrics.density
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        statusBarHeight = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

        val initialWidth = (screenWidth * 0.55f).roundToInt().coerceAtLeast(minVideoWidth())
        val initialHeight = videoHeightFor(initialWidth) + bottomBarHeight()

        val lp = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - initialWidth) / 2
            y = (screenHeight - initialHeight) / 2
        }
        params = lp

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingWindowService)
            setViewTreeViewModelStoreOwner(this@FloatingWindowService)
            setViewTreeSavedStateRegistryOwner(this@FloatingWindowService)
            setContent {
                FloatingWindowContent(
                    session = session,
                    outsideActions = outsideActions,
                    moreActions = moreActions,
                    onDrag = ::dragBy,
                    onResize = ::resizeBy,
                    onAction = ::dispatchAction,
                    onSurfaceAvailable = ::onSurfaceAvailable,
                    onSurfaceDestroyed = ::onSurfaceDestroyed,
                )
            }
        }
        root = composeView
        runCatching { windowManager.addView(composeView, lp) }
            .onFailure { Log.e(TAG, "addView failed", it) }
    }

    private fun observeSession() {
        scope.launch {
            val scrcpy = AppRuntime.scrcpy ?: return@launch
            scrcpy.currentSessionState.collect { info ->
                session = info
                applyAspect(info)
            }
        }
    }

    private fun observeActions() {
        scope.launch {
            appSettings.bundleState.collect { bundle ->
                val (outside, more) = VirtualButtonActions.splitLayout(
                    VirtualButtonActions.parseStoredLayout(bundle.virtualButtonsLayout),
                )
                outsideActions = outside.filter { it != VirtualButtonAction.MORE }
                moreActions = more
            }
        }
    }

    /** 会话宽高变化时，按新比例修正窗口高度，保持画面不变形。 */
    private fun applyAspect(info: Scrcpy.Session.SessionInfo?) {
        val lp = params ?: return
        val aspect = info
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height.toFloat() }
            ?: DEFAULT_ASPECT
        videoAspect = aspect
        lp.width = lp.width.coerceIn(minVideoWidth(), maxVideoWidth())
        lp.height = videoHeightFor(lp.width) + bottomBarHeight()
        clampPosition(lp)
        runCatching { windowManager.updateViewLayout(root, lp) }
    }

    // ------------------------------------------------------------------
    // 拖动 / 缩放
    // ------------------------------------------------------------------

    private fun dragBy(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x += dx.roundToInt()
        lp.y += dy.roundToInt()
        clampPosition(lp)
        runCatching { windowManager.updateViewLayout(root, lp) }
    }

    /** 仅以水平位移驱动缩放，并强制保持画面长宽比。 */
    private fun resizeBy(dx: Float) {
        val lp = params ?: return
        lp.width = (lp.width + dx.roundToInt()).coerceIn(minVideoWidth(), maxVideoWidth())
        lp.height = videoHeightFor(lp.width) + bottomBarHeight()
        clampPosition(lp)
        runCatching { windowManager.updateViewLayout(root, lp) }
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val minX = -lp.width / 3
        val maxX = screenWidth - lp.width / 3
        lp.x = lp.x.coerceIn(minX, maxX)
        lp.y = lp.y.coerceIn(statusBarHeight, (screenHeight - lp.height / 2).coerceAtLeast(statusBarHeight))
    }

    private fun minVideoWidth(): Int = (MIN_VIDEO_WIDTH_DP * density).roundToInt()

    private fun maxVideoWidth(): Int {
        val byHeight = ((screenHeight - bottomBarHeight()) * videoAspect).roundToInt()
        return (screenWidth.coerceAtMost(byHeight)).coerceAtLeast(minVideoWidth())
    }

    private fun videoHeightFor(width: Int): Int =
        (width / videoAspect).roundToInt().coerceAtLeast(1)

    private fun bottomBarHeight(): Int = (BOTTOM_BAR_DP * density).roundToInt()

    // ------------------------------------------------------------------
    // 视频绑定
    // ------------------------------------------------------------------

    private fun onSurfaceAvailable(holder: SurfaceHolder) {
        val surface = holder.surface
        if (!surface.isValid) return
        attachedSurface = surface
        scope.launch {
            runCatching { NativeCoreFacade.attachVideoSurface(surface) }
                .onFailure { Log.w(TAG, "attachVideoSurface failed", it) }
        }
    }

    private fun onSurfaceDestroyed() {
        val surface = attachedSurface ?: return
        attachedSurface = null
        scope.launch { runCatching { NativeCoreFacade.detachVideoSurface(surface) } }
    }

    // ------------------------------------------------------------------
    // 按键动作
    // ------------------------------------------------------------------

    private fun dispatchAction(action: VirtualButtonAction) {
        val scrcpy = AppRuntime.scrcpy ?: return
        when (action) {
            VirtualButtonAction.PASTE_LOCAL_CLIPBOARD -> scope.launch {
                val text = LocalInputService.getClipboardText(this@FloatingWindowService)
                    ?.takeIf { it.isNotBlank() }
                if (text == null) return@launch
                val legacy = session?.legacyPaste ?: false
                runCatching {
                    if (legacy) scrcpy.injectText(text)
                    else scrcpy.setClipboard(text, paste = true)
                }.onFailure { Log.w(TAG, "paste failed", it) }
            }

            VirtualButtonAction.ALL_APPS,
            VirtualButtonAction.RECENT_TASKS,
            VirtualButtonAction.TOGGLE_IME,
            VirtualButtonAction.PASSWORD_INPUT,
                -> Toast.makeText(
                    this,
                    getString(R.string.floating_window_action_needs_app),
                    Toast.LENGTH_SHORT,
                ).show()

            else -> action.keycode?.let { keycode ->
                scope.launch {
                    runCatching {
                        scrcpy.injectKeycode(0, keycode)
                        scrcpy.injectKeycode(1, keycode)
                    }.onFailure { Log.w(TAG, "injectKeycode failed for $keycode", it) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 前台服务
    // ------------------------------------------------------------------

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
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingWindowService::class.java).setAction(ACTION_STOP),
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
            .setContentTitle(getString(R.string.floating_window_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.floating_window_close),
                    stopIntent,
                ).build(),
            )
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

// ----------------------------------------------------------------------
// Compose 内容
// ----------------------------------------------------------------------

@Composable
private fun FloatingWindowContent(
    session: Scrcpy.Session.SessionInfo?,
    outsideActions: List<VirtualButtonAction>,
    moreActions: List<VirtualButtonAction>,
    onDrag: (Float, Float) -> Unit,
    onResize: (Float) -> Unit,
    onAction: (VirtualButtonAction) -> Unit,
    onSurfaceAvailable: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) =
                                onSurfaceAvailable(holder)

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = onSurfaceAvailable(holder)

                            override fun surfaceDestroyed(holder: SurfaceHolder) =
                                onSurfaceDestroyed()
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (session == null) {
                Text(
                    text = stringResource(R.string.floating_window_disconnected),
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            DragBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
                onDrag = onDrag,
                onTap = { showMenu = !showMenu },
            )

            if (showMenu && moreActions.isNotEmpty()) {
                ActionMenu(
                    actions = moreActions,
                    onAction = {
                        onAction(it)
                        showMenu = false
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 38.dp),
                )
            }

            // 右下手柄：透明度为 0，不显示热区，仅保留触摸区
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(width = 44.dp, height = 30.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onResize(dragAmount.x)
                        }
                    },
            )
        }

        if (outsideActions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color.Black.copy(alpha = 0.4f)),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                outsideActions.forEach { action ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onAction(action) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = stringResource(action.titleResId),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DragBar(
    modifier: Modifier,
    onDrag: (Float, Float) -> Unit,
    onTap: () -> Unit,
) {
    Box(
        modifier = modifier
            .width(96.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.22f))
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.6f)),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionMenu(
    actions: List<VirtualButtonAction>,
    onAction: (VirtualButtonAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = 5,
    ) {
        actions.forEach { action ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction(action) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = stringResource(action.titleResId),
                    tint = Color.White,
                )
            }
        }
    }
}
