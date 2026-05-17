package io.github.miuzarte.scrcpyforandroid.pages

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.util.Rational
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.LocalSnackbarController
import io.github.miuzarte.scrcpyforandroid.services.SnackbarController
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import io.github.miuzarte.scrcpyforandroid.ui.createThemeController
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTarget
import io.github.miuzarte.scrcpyforandroid.widgets.VideoOutputTargetState
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StreamScreen(activity: StreamActivity) {
    val scrcpy = remember { AppRuntime.scrcpy!! }
    val asBundle by Storage.appSettings.bundleState.collectAsState()

    val isInPip by activity.pipModeState.collectAsState()

    var pipSourceRectHint by remember { mutableStateOf<Rect?>(null) }
    var lastPipAspectRatio by remember { mutableStateOf<Rational?>(null) }
    var lastPipOrientationLandscape by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(isInPip) {
        onDispose {
            VideoOutputTargetState.set(VideoOutputTarget.PREVIEW)
        }
    }

    val currentSession by scrcpy.currentSessionState.collectAsState()

    LaunchedEffect(
        activity, isInPip,
        currentSession?.width, currentSession?.height,
    ) {
        val session = currentSession ?: return@LaunchedEffect

        val isLandscape = session.width >= session.height
        if (lastPipAspectRatio != null && lastPipOrientationLandscape == isLandscape) {
            // 一定要只在视频比例变更时才更新,
            // .setAspectRatio() 多次传递相同的值时,
            // 内部会自行应用其倒数
            return@LaunchedEffect
        }
        lastPipOrientationLandscape = isLandscape

        val pipAspectRatio = Rational(
            session.width.coerceAtLeast(1),
            session.height.coerceAtLeast(1),
        ).also { ratio ->
            lastPipAspectRatio = ratio
        }

        activity.configurePip {
            setEnabled(true)
            setAspectRatio(pipAspectRatio)
            setSourceRectHint(pipSourceRectHint)
            setSeamlessResizeEnabled(true)
            setCloseAction(activity.pipStopAction)
        }
    }

    val themeController = remember(
        asBundle.themeBaseIndex,
        asBundle.monet,
        asBundle.monetSeedIndex,
        asBundle.monetPaletteStyle,
        asBundle.monetColorSpec,
    ) {
        asBundle.createThemeController()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarScope, snackbarHostState) {
        SnackbarController(scope = snackbarScope, hostState = snackbarHostState)
    }
    DisposableEffect(snackbarHostState) {
        val unregister = AppRuntime.registerSnackbarHostState(snackbarHostState)
        onDispose(unregister)
    }

    MiuixTheme(
        controller = themeController,
    ) {
        CompositionLocalProvider(
            LocalSnackbarController provides snackbarController,
        ) {
            FullscreenControlRoute(
                scrcpy = scrcpy,
                onBack = activity::finish,
                isInPip = isInPip,
                onVideoBoundsInWindowChanged = {
                    pipSourceRectHint = it
                },
            )

        }
    }
}


@Composable
fun FullscreenControlRoute(
    scrcpy: Scrcpy,
    onBack: () -> Unit,
    isInPip: Boolean = false,
    autoExitOnStop: Boolean = false,
    onVideoBoundsInWindowChanged: (Rect?) -> Unit = {},
) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val asBundle by Storage.appSettings.bundleState.collectAsState()
    val currentSession by scrcpy.currentSessionState.collectAsState()

    // 自动重连逻辑 - 使用可变变量支持递归调用
    var handleReconnect: (() -> Unit)? = null
    handleReconnect = {
        val lastOptions = AppRuntime.lastClientOptions
        val isScrcpyRunning = scrcpy.isStarted()
        val connectionTarget = AppRuntime.currentConnectionTarget
        android.util.Log.i("StreamScreen", "onReconnectRequested: lastOptions=${lastOptions != null}, scrcpyRunning=$isScrcpyRunning, target=$connectionTarget, enabled=${AppRuntime.autoReconnectEnabled}, attempt=${AppRuntime.currentReconnectAttempt}/${AppRuntime.maxReconnectAttempts}")

        if (lastOptions != null && AppRuntime.autoReconnectEnabled &&
            AppRuntime.currentReconnectAttempt < AppRuntime.maxReconnectAttempts
        ) {
            AppRuntime.currentReconnectAttempt++
            android.util.Log.i("StreamScreen", "Auto-reconnecting (attempt ${AppRuntime.currentReconnectAttempt}/${AppRuntime.maxReconnectAttempts})")

            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // 1. 停止当前 scrcpy session
                    if (isScrcpyRunning) {
                        android.util.Log.i("StreamScreen", "Stopping current session...")
                        scrcpy.stop()
                        android.util.Log.i("StreamScreen", "Session stopped, waiting for cleanup...")
                        kotlinx.coroutines.delay(500)
                    }

                    // 2. 检查并重新建立 ADB 连接（关键修复）
                    val adbConnected = try {
                        io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService.isConnected()
                    } catch (e: Exception) {
                        false
                    }

                    if (!adbConnected && connectionTarget != null) {
                        android.util.Log.w("StreamScreen", "ADB not connected, reconnecting to ${connectionTarget.host}:${connectionTarget.port}...")
                        try {
                            io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService.connect(
                                host = connectionTarget.host,
                                port = connectionTarget.port,
                                timeout = kotlin.time.Duration.parse("10s")
                            )
                            android.util.Log.i("StreamScreen", "ADB reconnected successfully")
                            kotlinx.coroutines.delay(500)
                        } catch (e: Exception) {
                            android.util.Log.e("StreamScreen", "Failed to reconnect ADB", e)
                            throw e
                        }
                    } else if (adbConnected) {
                        android.util.Log.i("StreamScreen", "ADB still connected")
                    }

                    // 3. 杀掉服务器端可能残留的 scrcpy server 进程
                    try {
                        android.util.Log.i("StreamScreen", "Killing any zombie scrcpy server on remote...")
                        io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService.shell("pkill -9 -f 'com.genymobile.scrcpy.Server'")
                        android.util.Log.i("StreamScreen", "Zombie processes killed")
                        kotlinx.coroutines.delay(300)
                    } catch (e: Exception) {
                        android.util.Log.w("StreamScreen", "Failed to kill zombie processes (may be none)", e)
                    }

                    // 4. 重新启动
                    android.util.Log.i("StreamScreen", "Starting new session with saved options...")
                    scrcpy.start(lastOptions)
                    android.util.Log.i("StreamScreen", "Reconnect started successfully, waiting for frames...")
                } catch (e: Exception) {
                    android.util.Log.e("StreamScreen", "Reconnect failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (AppRuntime.currentReconnectAttempt >= AppRuntime.maxReconnectAttempts) {
                        android.util.Log.w("StreamScreen", "Max reconnect attempts reached, exiting")
                        // 清理重连计数，但保留 lastClientOptions 以便手动重试
                        AppRuntime.currentReconnectAttempt = 0
                        // 最后清理
                        try {
                            android.util.Log.i("StreamScreen", "Final cleanup: stopping scrcpy...")
                            scrcpy.stop()
                            kotlinx.coroutines.delay(300)
                        } catch (stopEx: Exception) {
                            android.util.Log.e("StreamScreen", "Error during final cleanup", stopEx)
                        }
                        android.util.Log.i("StreamScreen", "Exiting to menu...")
                        onBack()
                    } else {
                        android.util.Log.i("StreamScreen", "Will retry in 3 seconds...")
                        kotlinx.coroutines.delay(3000)
                        handleReconnect?.invoke()
                    }
                }
            }
        } else {
            android.util.Log.w("StreamScreen", "Cannot auto-reconnect: lastOptions=${lastOptions != null}, enabled=${AppRuntime.autoReconnectEnabled}, attempt=${AppRuntime.currentReconnectAttempt}")
            onBack()
        }
    }

    // 移除自动退出逻辑：当 session 断开时不自动退出，让用户可以手动重连

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(lifecycleOwner, autoExitOnStop) {
        if (!autoExitOnStop) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    onBack()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    FullscreenControlScreen(
        scrcpy = scrcpy,
        onBack = onBack,
        isInPip = isInPip,
        onVideoSizeChanged = { width, height ->
            if (!isInPip) {
                activity?.requestedOrientation =
                    fullscreenRequestedOrientation(
                        width = width,
                        height = height,
                        ignoreSystemRotationLock = asBundle.fullscreenControlIgnoreSystemRotationLock,
                    )
            }
        },
        onVideoBoundsInWindowChanged = onVideoBoundsInWindowChanged,
        onReconnectRequested = handleReconnect,
    )
}

private fun fullscreenRequestedOrientation(
    width: Int,
    height: Int,
    ignoreSystemRotationLock: Boolean,
) = if (width >= height) {
    if (ignoreSystemRotationLock)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
} else {
    if (ignoreSystemRotationLock)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    else
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}
