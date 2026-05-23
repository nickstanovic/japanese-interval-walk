package com.personal.japaneseintervalwalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(WalkUiState())
    private var receiverRegistered = false
    private var pendingStartAfterPermission = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            uiState = WalkUiState.fromIntent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()

        setContent {
            IntervalWalkTheme {
                IntervalWalkScreen(
                    state = uiState,
                    onStartStop = {
                        if (uiState.running) {
                            sendServiceCommand(IntervalService.ACTION_STOP, foregroundStart = false)
                            uiState = WalkUiState()
                        } else {
                            startWithNotificationPermission()
                        }
                    },
                    onPauseResume = {
                        if (uiState.running) {
                            sendServiceCommand(
                                if (uiState.paused) {
                                    IntervalService.ACTION_RESUME
                                } else {
                                    IntervalService.ACTION_PAUSE
                                },
                                foregroundStart = false,
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerStatusReceiver()
        requestStatus()
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            sendServiceCommand(IntervalService.ACTION_START, foregroundStart = true)
        }
    }

    private fun configureSystemBars() {
        val background = Color(0xFFF7FBF8).toArgb()
        val window: Window = window
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    private fun startWithNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        sendServiceCommand(IntervalService.ACTION_START, foregroundStart = true)
    }

    private fun sendServiceCommand(action: String, foregroundStart: Boolean) {
        val intent = Intent(this, IntervalService::class.java).setAction(action)
        if (foregroundStart) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestStatus() {
        startService(Intent(this, IntervalService::class.java).setAction(IntervalService.ACTION_REQUEST_STATUS))
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStatusReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter(IntervalService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 42
    }
}

data class WalkUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val slow: Boolean = true,
    val remainingMs: Long = IntervalService.INTERVAL_MS,
    val intervalMs: Long = IntervalService.INTERVAL_MS,
    val completedIntervals: Int = 0,
    val elapsedMs: Long = 0L,
) {
    val paceLabel: String
        get() = if (slow) "Slow walk" else "Fast walk"

    val statusLabel: String
        get() = when {
            !running -> "Ready"
            paused -> "Paused on ${paceLabel.lowercase(Locale.US)}"
            else -> paceLabel
        }

    val detailLabel: String
        get() = when {
            !running -> "Start with a slow walk."
            paused -> "Paused. Resume keeps this interval intact."
            else -> "Elapsed ${elapsedMs.formatDuration()} / current interval"
        }

    val nextCueLabel: String
        get() = if (slow) {
            "Next cue: two short vibrations for fast."
        } else {
            "Next cue: one long vibration for slow."
        }

    val progress: Float
        get() {
            val interval = max(1L, intervalMs)
            val elapsedInInterval = (interval - remainingMs).coerceIn(0L, interval)
            return elapsedInInterval.toFloat() / interval.toFloat()
        }

    companion object {
        fun fromIntent(intent: Intent): WalkUiState {
            val intervalMs = intent.getLongExtra(IntervalService.EXTRA_INTERVAL_MS, IntervalService.INTERVAL_MS)
            return WalkUiState(
                running = intent.getBooleanExtra(IntervalService.EXTRA_RUNNING, false),
                paused = intent.getBooleanExtra(IntervalService.EXTRA_PAUSED, false),
                slow = intent.getBooleanExtra(IntervalService.EXTRA_SLOW, true),
                remainingMs = intent.getLongExtra(IntervalService.EXTRA_REMAINING_MS, intervalMs),
                intervalMs = intervalMs,
                completedIntervals = intent.getIntExtra(IntervalService.EXTRA_COMPLETED_INTERVALS, 0),
                elapsedMs = intent.getLongExtra(IntervalService.EXTRA_ELAPSED_MS, 0L),
            )
        }
    }
}

@Composable
private fun IntervalWalkTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val baseScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        lightColorScheme()
    }
    val scheme = baseScheme.copy(
        primary = SlowGreen,
        secondary = FastCoral,
        surface = Color.White,
        background = AppBackground,
        onPrimary = Color.White,
        onSecondary = Color.White,
        onSurface = Ink,
        onBackground = Ink,
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}

@Composable
private fun IntervalWalkScreen(
    state: WalkUiState,
    onStartStop: () -> Unit,
    onPauseResume: () -> Unit,
) {
    val paceColor = if (state.slow) SlowGreen else FastCoral
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon_full),
                contentDescription = null,
                modifier = Modifier
                    .size(116.dp)
                    .clip(RoundedCornerShape(26.dp)),
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Interval Walk",
                color = Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp,
            )

            Text(
                text = "3 min slow / 3 min fast",
                color = Ink.copy(alpha = 0.72f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            PaceChip(label = state.statusLabel, color = paceColor)

            Spacer(Modifier.height(14.dp))

            Text(
                text = state.remainingMs.formatDuration(),
                color = paceColor,
                fontSize = 76.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 82.sp,
            )

            Spacer(Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp)),
                color = paceColor,
                trackColor = Color(0xFFDEE8E1),
            )

            Spacer(Modifier.height(24.dp))

            StatusPanel(state = state)

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onStartStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.running) Color(0xFF272D2A) else SlowGreen,
                    ),
                ) {
                    Text(
                        text = if (state.running) "Stop" else "Start",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Button(
                    onClick = onPauseResume,
                    enabled = state.running,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = paceColor,
                        disabledContainerColor = Color(0xFFBEC7C1),
                        disabledContentColor = Color.White.copy(alpha = 0.85f),
                    ),
                ) {
                    Text(
                        text = if (state.paused) "Resume" else "Pause",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PaceChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusPanel(state: WalkUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.detailLabel,
                color = Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Intervals completed: ${state.completedIntervals}",
                color = Ink.copy(alpha = 0.72f),
                fontSize = 15.sp,
            )
            Text(
                text = state.nextCueLabel,
                color = Ink.copy(alpha = 0.72f),
                fontSize = 15.sp,
            )
        }
    }
}

private fun Long.formatDuration(): String {
    val totalSeconds = max(0L, (this + 999L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

private val AppBackground = Color(0xFFF7FBF8)
private val Ink = Color(0xFF17231D)
private val SlowGreen = Color(0xFF2F7D5C)
private val FastCoral = Color(0xFFD9574E)
