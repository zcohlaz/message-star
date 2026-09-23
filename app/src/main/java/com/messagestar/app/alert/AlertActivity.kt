package com.messagestar.app.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.messagestar.app.data.AlertStateStore
import com.messagestar.app.data.AlertSnapshot
import com.messagestar.app.ui.MessageStarTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertActivity : ComponentActivity() {
    // Activity 属性初始化早于系统 attachBaseContext，不能在这里读取 SharedPreferences.
    private var snapshot by mutableStateOf(AlertSnapshot(false, 0, "", 0L))
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            snapshot = AlertStateStore.snapshot(this@AlertActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // 报警期间忽略返回操作，只允许关闭按钮或音量键停止报警。
                }
            }
        )
        snapshot = AlertStateStore.snapshot(this)
        setContent {
            MessageStarTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF160F24)) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("重要${snapshot.sourceType}", style = MaterialTheme.typography.displaySmall, color = Color.White)
                        Spacer(Modifier.height(20.dp))
                        Text("收到 ${snapshot.count.coerceAtLeast(1)} 条重要${snapshot.sourceType}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                        Spacer(Modifier.height(14.dp))
                        Text("来源：${snapshot.latestSender.ifBlank { "未知" }}", color = Color(0xFFE6DFF5))
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), color = Color(0xFFE6DFF5))
                        Spacer(Modifier.height(44.dp))
                        Button(onClick = { closeAlert() }, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                            Text("关闭提醒", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        snapshot = AlertStateStore.snapshot(this)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AlertNotification.ACTION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                closeAlert()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun closeAlert() {
        AlertCoordinator.stop(this, cooldown = true)
        finishAndRemoveTask()
    }
}
