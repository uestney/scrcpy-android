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
import io.github.miuzarte.scrcpyforandroid.nativecore.MicTestUtil
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

            // 录音测试面板（叠在画面上方）
            MicTestPanel()
        }
    }
}

@Composable
fun MicTestPanel() {
    var recording by remember { mutableStateOf(false) }
    var recorded   by remember { mutableStateOf<ByteArray?>(null) }
    var playing    by remember { mutableStateOf(false) }
    var status     by remember { mutableStateOf("") }
    val scope      = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.End
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xCC000000), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Mic Test", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            if (status.isNotBlank()) {
                Text(status, color = Color.Yellow, fontSize = 11.sp, maxLines = 3)
                Spacer(Modifier.height(4.dp))
            }
            Row {
                Button(
                    onClick = {
                        if (recording) return@Button
                        recording = true
                        status = "Recording 3s..."
                        scope.launch {
                            val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                MicTestUtil.record()
                            }
                            recording = false
                            if (data != null) {
                                recorded = data
                                status = "OK! ${data.size} bytes"
                            } else {
                                status = "FAIL: ${MicTestUtil.lastError}"
                            }
                        }
                    },
                    enabled = !recording && !playing,
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color.Red else Color(0xFF4CAF50))
                ) {
                    Text(if (recording) "..." else "Record", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val data = recorded ?: return@Button
                        playing = true
                        status = "Playing..."
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                MicTestUtil.play(data)
                            }
                            playing = false
                            status = "Play done"
                        }
                    },
                    enabled = recorded != null && !recording && !playing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text(if (playing) "..." else "Play", color = Color.White, fontSize = 12.sp)
                }
            }
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

    LaunchedEffect(currentSession) {
        if (currentSession == null) {
            onBack()
        }
    }

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
