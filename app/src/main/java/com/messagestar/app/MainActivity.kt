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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.messagestar.app.alert.AlertCoordinator
import com.messagestar.app.data.Rule
import com.messagestar.app.data.RuleType
import com.messagestar.app.data.SettingsRepository
import com.messagestar.app.permissions.DeviceSettingsHelper
import com.messagestar.app.notification.NotificationManagementScreen
import com.messagestar.app.rules.RuleEngine
import com.messagestar.app.ui.MessageStarTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private object AppScreen {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PERMISSIONS = "permissions"
    const val NOTIFICATIONS = "notifications"
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository

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
        lifecycleScope.launch { repository.block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageStarApp(repository: SettingsRepository, activity: MainActivity) {
    val rules by repository.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val masterEnabled by repository.masterEnabled.collectAsStateWithLifecycle(initialValue = true)
    val vibrationEnabled by repository.vibrationEnabled.collectAsStateWithLifecycle(initialValue = true)
    val ringtoneUri by repository.ringtoneUri.collectAsStateWithLifecycle(initialValue = null)
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var permissionRevision by remember { mutableIntStateOf(0) }
    var editingRuleJson by rememberSaveable { mutableStateOf<String?>(null) }
    var showTypePicker by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteJson by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = AppScreen.HOME) {
            composable(AppScreen.HOME) {
                Scaffold(topBar = { TopAppBar(title = { Text("短信强提醒", fontWeight = FontWeight.Bold) }) }) { padding ->
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        rules = rules,
                        masterEnabled = masterEnabled,
                        permissionRevision = permissionRevision,
                        onPermissionResult = { permissionRevision++ },
                        onMasterChanged = { activity.save { setMasterEnabled(it) }; scope.launch { snackbarHostState.showSnackbar(if (it) "强提醒已开启" else "强提醒已暂停") } },
                        onRuleChanged = { changed ->
                            scope.launch {
                                repository.saveRules(repository.currentRules().map { if (it.id == changed.id) changed else it })
                                snackbarHostState.showSnackbar(if (changed.enabled) "规则已开启" else "规则已暂停")
                            }
                        },
                        onEdit = { editingRuleJson = Json.encodeToString(it) },
                        onDelete = { pendingDeleteJson = Json.encodeToString(it) },
                        onAdd = { if (rules.size < 10) showTypePicker = true },
                        onSettings = { navController.navigate(AppScreen.SETTINGS) },
                        onPermission = { navController.navigate(AppScreen.PERMISSIONS) },
                        onNotificationManagement = { navController.navigate(AppScreen.NOTIFICATIONS) }
                    )
                }
            }
            composable(AppScreen.SETTINGS) {
                SettingsScreen(
                    vibrationEnabled = vibrationEnabled,
                    ringtoneUri = ringtoneUri,
                    onBack = { navController.popBackStack() },
                    onVibrationChanged = { value ->
                        activity.save { setVibrationEnabled(value) }
                        scope.launch { snackbarHostState.showSnackbar(if (value) "振动已开启" else "振动已关闭") }
                    },
                    onRingtoneSelected = { uri ->
                        activity.save { setRingtoneUri(uri?.toString()) }
                        scope.launch { snackbarHostState.showSnackbar("铃声已更新") }
                    },
                    onPermission = { navController.navigate(AppScreen.PERMISSIONS) },
                    onTest = { AlertCoordinator.trigger(activity, "测试短信", testMode = true) }
                )
            }
            composable(AppScreen.NOTIFICATIONS) {
                NotificationManagementScreen(repository, permissionRevision, onBack = { navController.popBackStack() })
            }
            composable(AppScreen.PERMISSIONS) {
                PermissionScreen(
                    onBack = { navController.popBackStack() },
                    permissionRevision = permissionRevision,
                    onPermissionResult = { permissionRevision++ }
                )
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showTypePicker) {
        AlertDialog(
            onDismissRequest = { showTypePicker = false },
            title = { Text("选择规则类型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        showTypePicker = false
                        editingRuleJson = Json.encodeToString(Rule(java.util.UUID.randomUUID().toString(), RuleType.PHONE))
                    }, modifier = Modifier.fillMaxWidth()) { Text("手机号") }
                    OutlinedButton(onClick = {
                        showTypePicker = false
                        editingRuleJson = Json.encodeToString(Rule(java.util.UUID.randomUUID().toString(), RuleType.PLATFORM))
                    }, modifier = Modifier.fillMaxWidth()) { Text("平台短信") }
                }
            },
            confirmButton = {}
        )
    }

    editingRuleJson?.let { encoded ->
        val original = remember(encoded) { Json.decodeFromString<Rule>(encoded) }
        RuleEditorDialog(
            initial = original,
            existingRules = rules,
            onDismiss = { editingRuleJson = null },
            onSave = { saved ->
                scope.launch {
                    val current = repository.currentRules()
                    val next = if (current.any { it.id == saved.id }) current.map { if (it.id == saved.id) saved else it } else current + saved
                    repository.saveRules(next)
                    editingRuleJson = null
                    snackbarHostState.showSnackbar("规则已保存")
                }
            }
        )
    }

    pendingDeleteJson?.let { encoded ->
        val target = remember(encoded) { Json.decodeFromString<Rule>(encoded) }
        AlertDialog(
            onDismissRequest = { pendingDeleteJson = null },
            title = { Text("确定删除该监听规则？") },
            text = { Text(target.title) },
            dismissButton = { TextButton(onClick = { pendingDeleteJson = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteJson = null
                    scope.launch {
                        val before = repository.currentRules()
                        val position = before.indexOfFirst { it.id == target.id }
                        if (position < 0) return@launch
                        repository.saveRules(before.filterNot { it.id == target.id })
                        if (snackbarHostState.showSnackbar("规则已删除", actionLabel = "撤销") == SnackbarResult.ActionPerformed) {
                            val current = repository.currentRules()
                            if (current.none { it.id == target.id }) {
                                repository.saveRules(current.toMutableList().apply { add(position.coerceAtMost(size), target) })
                            }
                        }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
@Composable
private fun HomeScreen(
    modifier: Modifier,
    rules: List<Rule>,
    masterEnabled: Boolean,
    permissionRevision: Int,
    onPermissionResult: () -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onRuleChanged: (Rule) -> Unit,
    onEdit: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onPermission: () -> Unit,
    onNotificationManagement: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val smsGranted = remember(permissionRevision) { ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED }
    val notificationGranted = remember(permissionRevision) { Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val fullScreenGranted = remember(permissionRevision) { Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    val overlayGranted = remember(permissionRevision) { Settings.canDrawOverlays(context) }
    val healthy = smsGranted && notificationGranted && (fullScreenGranted || overlayGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionResult() }

    val listState = rememberLazyListState()
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        OutlinedButton(onClick = onNotificationManagement, modifier = Modifier.fillMaxWidth()) { Text("通知筛选与收纳") }
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
private fun PermissionScreen(onBack: () -> Unit, permissionRevision: Int, onPermissionResult: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageName = context.packageName
    val sms = remember(permissionRevision) { ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED }
    val notifications = remember(permissionRevision) { Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val fullScreen = remember(permissionRevision) { Build.VERSION.SDK_INT < 34 || context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent() }
    val overlay = remember(permissionRevision) { Settings.canDrawOverlays(context) }
    val notificationListener = remember(permissionRevision) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(packageName) }
    val power = remember(permissionRevision) { context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true }
    val manufacturer = DeviceSettingsHelper.manufacturerName()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionResult() }
    Scaffold(topBar = { TopAppBar(title = { Text("权限检查") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("为了尽可能可靠地提醒，请检查以下项目。vivo 的菜单名称可能随 OriginOS 版本变化。", style = MaterialTheme.typography.bodyMedium) }
            item { PermissionRow("SMS 接收权限", sms, onClick = { launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE)) }) }
            item { PermissionRow("通知权限", notifications, onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) }
            item { PermissionRow("通知使用权", notificationListener, onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) }
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
private fun RuleEditorDialog(
    initial: Rule,
    existingRules: List<Rule>,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit
) {
    var phone by rememberSaveable(initial.id) { mutableStateOf(initial.phoneNumber) }
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var keywordsText by rememberSaveable(initial.id) { mutableStateOf(initial.keywords.joinToString("\n")) }
    var testBody by rememberSaveable(initial.id) { mutableStateOf("") }
    var error by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var testResult by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by rememberSaveable(initial.id) { mutableStateOf(false) }
    val platform = initial.type == RuleType.PLATFORM
    val enteredKeywords = keywordsText.lines().map(String::trim).filter(String::isNotEmpty)
    val keywords = enteredKeywords.take(5)
    val hasChanges = if (platform) {
        name != initial.name || keywordsText != initial.keywords.joinToString("\n")
    } else {
        phone != initial.phoneNumber
    }
    val requestDismiss = {
        if (hasChanges) showDiscardConfirmation = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = requestDismiss,
        title = { Text(if (platform) "平台短信规则" else "手机号规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (platform) {
                    OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("规则名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = keywordsText, onValueChange = { keywordsText = it; error = null }, label = { Text("关键词（每行一个，最多 5 个）") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("建议配置 2～3 个稳定关键词，所有关键词都匹配才会触发。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    OutlinedTextField(value = testBody, onValueChange = { testBody = it }, label = { Text("输入测试短信正文") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        val result = RuleEngine.test(initial.copy(keywords = keywords), testBody)
                        testResult = if (result.matched) "✓ 会触发强提醒（命中 ${keywords.size}/${keywords.size} 个关键词）" else "✗ 不会触发：未匹配 ${result.missingKeywords.joinToString("、")}"
                    }, enabled = testBody.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("测试规则") }
                    testResult?.let { Text(it, color = if (it.startsWith("✓")) Color(0xFF1B8A4A) else MaterialTheme.colorScheme.error) }
                } else {
                    OutlinedTextField(value = phone, onValueChange = { phone = it; error = null }, label = { Text("手机号码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("支持 +86、86、空格和短横线格式，保存时会精确匹配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = requestDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                if (!platform) {
                    val normalized = RuleEngine.normalizePhone(phone)
                    if (!normalized.matches(Regex("1[3-9]\\d{9}"))) {
                        error = "请输入有效的大陆手机号"
                        return@TextButton
                    }
                    if (existingRules.any { it.id != initial.id && it.type == RuleType.PHONE && RuleEngine.normalizePhone(it.phoneNumber) == normalized }) {
                        error = "该手机号已有监听规则"
                        return@TextButton
                    }
                    onSave(initial.copy(phoneNumber = normalized))
                } else {
                    if (name.isBlank() || enteredKeywords.isEmpty()) {
                        error = "请填写规则名称和至少一个关键词"
                        return@TextButton
                    }
                    if (enteredKeywords.size > 5) {
                        error = "最多只能填写 5 个关键词"
                        return@TextButton
                    }
                    if (enteredKeywords.distinct().size != enteredKeywords.size) {
                        error = "关键词不能重复"
                        return@TextButton
                    }
                    onSave(initial.copy(name = name.trim(), keywords = enteredKeywords))
                }
            }) { Text("保存") }
        }
    )

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("返回后，本次编辑内容将丢失。") },
            dismissButton = { TextButton(onClick = { showDiscardConfirmation = false }) { Text("继续编辑") } },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    onDismiss()
                }) { Text("放弃修改", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}