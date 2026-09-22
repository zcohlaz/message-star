package com.messagestar.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messagestar.app.alert.AlertCoordinator
import com.messagestar.app.data.Rule
import com.messagestar.app.data.RuleType
import com.messagestar.app.data.SettingsRepository
import com.messagestar.app.permissions.DeviceSettingsHelper
import com.messagestar.app.rules.RuleEngine
import com.messagestar.app.ui.MessageStarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private enum class AppScreen { HOME, SETTINGS, PERMISSIONS }

class MainActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        setContent {
            MessageStarTheme {
                MessageStarApp(repository = repository, activity = this)
            }
        }
    }

    fun save(block: suspend SettingsRepository.() -> Unit) {
        activityScope.launch { repository.block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageStarApp(repository: SettingsRepository, activity: MainActivity) {
    val rules by repository.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val masterEnabled by repository.masterEnabled.collectAsStateWithLifecycle(initialValue = true)
    val vibrationEnabled by repository.vibrationEnabled.collectAsStateWithLifecycle(initialValue = true)
    val ringtoneUri by repository.ringtoneUri.collectAsStateWithLifecycle(initialValue = null)
    var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }
    var showTypePicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Rule?>(null) }
    val saveRules: (List<Rule>) -> Unit = { next -> activity.save { saveRules(next) } }

    when (screen) {
        AppScreen.HOME -> Scaffold(
            topBar = { TopAppBar(title = { Text("短信强提醒", fontWeight = FontWeight.Bold) }) }
        ) { padding ->
            HomeScreen(
                modifier = Modifier.padding(padding),
                rules = rules,
                masterEnabled = masterEnabled,
                onMasterChanged = { activity.save { setMasterEnabled(it) } },
                onRuleChanged = { changed -> saveRules(rules.map { if (it.id == changed.id) changed else it }) },
                onEdit = { editingRule = it },
                onDelete = { pendingDelete = it },
                onAdd = { if (rules.size < 10) showTypePicker = true },
                onSettings = { screen = AppScreen.SETTINGS },
                onPermission = { screen = AppScreen.PERMISSIONS }
            )
        }
        AppScreen.SETTINGS -> SettingsScreen(
            vibrationEnabled = vibrationEnabled,
            ringtoneUri = ringtoneUri,
            onBack = { screen = AppScreen.HOME },
            onVibrationChanged = { activity.save { setVibrationEnabled(it) } },
            onRingtoneSelected = { activity.save { setRingtoneUri(it?.toString()) } },
            onPermission = { screen = AppScreen.PERMISSIONS },
            onTest = { AlertCoordinator.trigger(activity, "测试短信", testMode = true) }
        )
        AppScreen.PERMISSIONS -> PermissionScreen(onBack = { screen = AppScreen.HOME })
    }

    if (showTypePicker) {
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text("选择规则类型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showTypePicker = false; editingRule = Rule(java.util.UUID.randomUUID().toString(), RuleType.PHONE) }, modifier = Modifier.fillMaxWidth()) { Text("手机号") }
                    OutlinedButton(onClick = { showTypePicker = false; editingRule = Rule(java.util.UUID.randomUUID().toString(), RuleType.PLATFORM) }, modifier = Modifier.fillMaxWidth()) { Text("平台短信") }
                }
            },
            confirmButton = {}
        )
    }

    editingRule?.let { original ->
        RuleEditorDialog(
            initial = original,
            onDismiss = { editingRule = null },
            onSave = { saved ->
                val next = if (rules.any { it.id == saved.id }) rules.map { if (it.id == saved.id) saved else it } else rules + saved
                saveRules(next)
                editingRule = null
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确定删除该监听规则？") },
            text = { Text(target.title) },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = { saveRules(rules.filterNot { it.id == target.id }); pendingDelete = null }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    rules: List<Rule>,
    masterEnabled: Boolean,
    onMasterChanged: (Boolean) -> Unit,
    onRuleChanged: (Rule) -> Unit,
    onEdit: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onPermission: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val notificationGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fullScreenGranted = Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    val overlayGranted = Settings.canDrawOverlays(context)
    val healthy = smsGranted && notificationGranted && (fullScreenGranted || overlayGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = if (healthy) Color(0xFFE9F8EE) else Color(0xFFFFF0EF)), modifier = Modifier.fillMaxWidth().clickable { onPermission() }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (healthy) "●" else "⚠", color = if (healthy) Color(0xFF1B8A4A) else Color(0xFFC62828), fontSize = 22.sp)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (healthy) "强提醒正常" else "强提醒功能可能无法正常工作", fontWeight = FontWeight.SemiBold)
                    Text(if (healthy) "短信到达时会按规则提醒" else "点击查看权限检查", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) { Text("强提醒", fontWeight = FontWeight.SemiBold); Text("关闭后暂停所有规则", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = masterEnabled, onCheckedChange = onMasterChanged)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("监听规则", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${rules.size}/10", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有监听规则", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("添加手机号或平台关键词，开始保护重要短信", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules, key = { it.id }) { rule ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rule.title.ifBlank { "未命名规则" }, fontWeight = FontWeight.SemiBold)
                                Text(if (rule.type == RuleType.PHONE) "手机号精确匹配" else "全部关键词匹配", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { onRuleChanged(rule.copy(enabled = it)) })
                            IconButton(onClick = { onEdit(rule) }) { Icon(Icons.Default.Edit, "编辑") }
                            IconButton(onClick = { onDelete(rule) }) { Icon(Icons.Default.Delete, "删除") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onAdd, enabled = rules.size < 10, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text(if (rules.size < 10) "添加规则" else "已达上限") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(0.55f)) { Icon(Icons.Default.Settings, null); Spacer(Modifier.size(4.dp)); Text("设置") }
        }
        if (!smsGranted || !notificationGranted) {
            TextButton(onClick = { launcher.launch(buildList { add(Manifest.permission.RECEIVE_SMS); add(Manifest.permission.READ_PHONE_STATE); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("申请基础权限") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    vibrationEnabled: Boolean,
    ringtoneUri: String?,
    onBack: () -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onRingtoneSelected: (Uri?) -> Unit,
    onPermission: () -> Unit,
    onTest: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ringtoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onRingtoneSelected(result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI))
    }
    val currentName = remember(ringtoneUri) {
        runCatching { ringtoneUri?.let { RingtoneManager.getRingtone(context, Uri.parse(it))?.getTitle(context) } }.getOrNull() ?: "系统默认闹钟铃声"
    }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("提醒铃声", fontWeight = FontWeight.SemiBold)
                    Text(currentName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ringtoneLauncher.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply { putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM); putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒铃声"); putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtoneUri?.let(Uri::parse)) }) }) { Text("选择") }
                        OutlinedButton(onClick = { ringtoneUri?.let { RingtoneManager.getRingtone(context, Uri.parse(it))?.play() } ?: RingtoneManager.getRingtone(context, Settings.System.DEFAULT_ALARM_ALERT_URI)?.play() }) { Text("试听") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("震动提醒", fontWeight = FontWeight.SemiBold); Text("震动 1 秒，停止 1 秒循环", style = MaterialTheme.typography.bodySmall) }; Switch(checked = vibrationEnabled, onCheckedChange = onVibrationChanged) } }
            Card(Modifier.fillMaxWidth().clickable(onClick = onTest)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.size(12.dp)); Column { Text("测试强提醒", fontWeight = FontWeight.SemiBold); Text("模拟一次报警，不需要真实短信", style = MaterialTheme.typography.bodySmall) } } }
            Card(Modifier.fillMaxWidth().clickable(onClick = onPermission)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("权限检查", fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("查看", color = MaterialTheme.colorScheme.primary) } }
            HorizontalDivider()
            Text("短信强提醒 V1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageName = context.packageName
    val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fullScreen = Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    val overlay = Settings.canDrawOverlays(context)
    val power = (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true)
    val manufacturer = DeviceSettingsHelper.manufacturerName()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Scaffold(topBar = { TopAppBar(title = { Text("权限检查") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("为了尽可能可靠地提醒，请检查以下项目。vivo 的菜单名称可能随 OriginOS 版本变化。", style = MaterialTheme.typography.bodyMedium) }
            item { PermissionRow("SMS 接收权限", sms, onClick = { launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE)) }) }
            item { PermissionRow("通知权限", notifications, onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) }
            item { PermissionRow("通话状态权限", ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED, onClick = { launcher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) }) }
            item { PermissionRow("全屏提醒权限", fullScreen, onClick = { if (Build.VERSION.SDK_INT >= 34) context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }) }
            item { PermissionRow("后台弹窗权限", overlay, onClick = { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) }
            item { PermissionRow("电池优化", power, onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } }) }
            item { PermissionRow("后台自启动 / 后台运行", false, statusText = "点击前往 ${manufacturer} 系统设置确认", onClick = { DeviceSettingsHelper.openAutoStartSettings(context) }) }
            item { Text("建议允许后台弹窗、自启动，并将电池后台策略设为不限制。报警结束后，应用会恢复原来的闹钟音量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, statusText: String? = null, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    statusText ?: if (granted) "正常" else "需要设置",
                    color = if (granted) Color(0xFF1B8A4A) else if (statusText != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(if (granted) "✓" else "→", color = if (granted) Color(0xFF1B8A4A) else MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        }
    }
}

@Composable
private fun RuleEditorDialog(initial: Rule, onDismiss: () -> Unit, onSave: (Rule) -> Unit) {
    var phone by remember(initial.id) { mutableStateOf(initial.phoneNumber) }
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var keywordsText by remember(initial.id) { mutableStateOf(initial.keywords.joinToString("\n")) }
    var testBody by remember(initial.id) { mutableStateOf("") }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var testResult by remember(initial.id) { mutableStateOf<String?>(null) }
    val platform = initial.type == RuleType.PLATFORM
    val keywords = keywordsText.lines().map(String::trim).filter(String::isNotEmpty).take(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (platform) "平台短信规则" else "手机号规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (platform) {
                    OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("规则名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = keywordsText, onValueChange = { keywordsText = it; error = null }, label = { Text("关键词（每行一个，最多 5 个）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("建议配置 2～3 个稳定关键词，所有关键词都匹配才会触发。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    OutlinedTextField(value = testBody, onValueChange = { testBody = it }, label = { Text("输入测试短信正文") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { val result = RuleEngine.test(initial.copy(keywords = keywords), testBody); testResult = if (result.matched) "✓ 会触发强提醒（命中 ${keywords.size}/${keywords.size} 个关键词）" else "✗ 不会触发：未匹配 ${result.missingKeywords.joinToString("、")}" }, enabled = testBody.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("测试规则") }
                    testResult?.let { Text(it, color = if (it.startsWith("✓")) Color(0xFF1B8A4A) else MaterialTheme.colorScheme.error) }
                } else {
                    OutlinedTextField(value = phone, onValueChange = { phone = it; error = null }, label = { Text("手机号码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("支持 +86、86、空格和短横线格式，保存时会精确匹配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                if (!platform && RuleEngine.normalizePhone(phone).length < 11) { error = "请输入有效的手机号"; return@TextButton }
                if (platform && (name.isBlank() || keywords.isEmpty())) { error = "请填写规则名称和至少一个关键词"; return@TextButton }
                onSave(if (platform) initial.copy(name = name.trim(), keywords = keywords) else initial.copy(phoneNumber = RuleEngine.normalizePhone(phone)))
            }) { Text("保存") }
        }
    )
}
