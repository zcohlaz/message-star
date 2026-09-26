package com.messagestar.app.notification

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.messagestar.app.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun NotificationManagementScreen(
    repository: SettingsRepository,
    permissionRevision: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by repository.notificationFiltering.collectAsStateWithLifecycle(initialValue = false)
    val managed by repository.managedPackages.collectAsStateWithLifecycle(initialValue = emptySet())
    val rules by repository.notificationRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val dbRevision by NotificationArchiveStore.changes.collectAsStateWithLifecycle()
    val hasAccess = remember(permissionRevision) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    var observed by remember { mutableStateOf<List<ObservedApp>>(emptyList()) }
    var archive by remember { mutableStateOf<List<ArchivedNotification>>(emptyList()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<NotificationRule?>(null) }
    var removing by remember { mutableStateOf<NotificationRule?>(null) }
    var clearArchive by remember { mutableStateOf(false) }

    LaunchedEffect(dbRevision) {
        val result = withContext(Dispatchers.IO) {
            NotificationArchiveStore(context.applicationContext).use { store ->
                store.observedApps() to store.archived()
            }
        }
        observed = result.first
        archive = result.second
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("筛选规则") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("收纳箱 ${archive.size}") })
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("启用通知筛选", fontWeight = FontWeight.SemiBold)
                                    Text("仅处理下方选定的 APP；其他 APP 保持原样", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = enabled, enabled = hasAccess, onCheckedChange = { value ->
                                    scope.launch { repository.setNotificationFiltering(value) }
                                })
                            }
                            if (!hasAccess) {
                                Text("先授予通知使用权。授权后，发送过通知的 APP 会出现在下方。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("打开通知使用权设置") }
                            } else {
                                Text("已获得通知使用权。受管 APP 的来源通知还需在系统中设为静默，并关闭横幅。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Text("管理的 APP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                if (observed.isEmpty()) item {
                    Text("暂未检测到其他 APP 的通知。授权后，让目标 APP 发来一条通知，即可在这里选择。", style = MaterialTheme.typography.bodyMedium)
                }
                items(observed, key = { "app:${it.packageName}" }) { app ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, fontWeight = FontWeight.SemiBold)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = app.packageName in managed, onCheckedChange = { checked ->
                                    scope.launch {
                                        val current = repository.currentManagedPackages()
                                        repository.saveManagedPackages(if (checked) current + app.packageName else current - app.packageName)
                                    }
                                })
                            }
                            if (app.packageName in managed) {
                                Text("未命中白名单的通知将进入收纳箱。请先将来源通知设为静默。", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName))
                                }) { Text("打开该 APP 的通知设置") }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("重要通知规则", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Button(enabled = managed.isNotEmpty(), onClick = {
                            editing = NotificationRule(UUID.randomUUID().toString(), managed.sorted().first())
                        }) { Text("添加规则") }
                    }
                }
                if (rules.isEmpty()) item { Text("还没有白名单规则。受管 APP 的普通通知会被静默收纳。", style = MaterialTheme.typography.bodySmall) }
                items(rules, key = { "rule:${it.id}" }) { rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { editing = rule }) {
                                Text(ruleLabel(rule), fontWeight = FontWeight.SemiBold)
                                Text("${rule.packageName} · ${actionLabel(rule.action)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = rule.enabled, onCheckedChange = { checked ->
                                scope.launch { repository.saveNotificationRules(repository.currentNotificationRules().map { if (it.id == rule.id) it.copy(enabled = checked) else it }) }
                            })
                            IconButton(onClick = { removing = rule }) { Icon(Icons.Default.Delete, "删除规则") }
                        }
                    }
                }
                item {
                    Text("规则按列表顺序匹配，第一条命中的规则生效。内容不可读取或持续运行的通知会保留。", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                item { Text("收纳箱只保存通知记录，原 APP 里的消息仍由原 APP 管理。记录最多保存 30 天、1000 条。", style = MaterialTheme.typography.bodySmall) }
                if (archive.isEmpty()) item { Text("还没有收纳的通知。", style = MaterialTheme.typography.bodyMedium) }
                if (archive.isNotEmpty()) item {
                    TextButton(onClick = { clearArchive = true }) { Text("清空收纳箱") }
                }
                items(archive, key = { "archive:${it.key}" }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.appLabel, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.postedAt)), style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.title.isNotBlank()) Text(entry.title)
                            if (entry.body.isNotBlank()) Text(entry.body, style = MaterialTheme.typography.bodySmall)
                            Text(entry.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { NotificationArchiveStore(context.applicationContext).use { it.delete(entry.key) } }
                            }) { Text("删除记录") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { current ->
        NotificationRuleDialog(current, managed.sorted(), onDismiss = { editing = null }, onSave = { updated ->
            scope.launch {
                val existing = repository.currentNotificationRules()
                repository.saveNotificationRules(if (existing.any { it.id == updated.id }) existing.map { if (it.id == updated.id) updated else it } else existing + updated)
                editing = null
            }
        })
    }
    removing?.let { target ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除这条规则？") },
            text = { Text(ruleLabel(target)) },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                scope.launch { repository.saveNotificationRules(repository.currentNotificationRules().filterNot { it.id == target.id }); removing = null }
            }) { Text("删除") } })
    }
    if (clearArchive) {
        AlertDialog(onDismissRequest = { clearArchive = false }, title = { Text("清空收纳箱？") },
            text = { Text("这会删除 MessageStar 保存的通知记录。") },
            dismissButton = { TextButton(onClick = { clearArchive = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                scope.launch(Dispatchers.IO) { NotificationArchiveStore(context.applicationContext).use { it.clearArchive() } }
                clearArchive = false
            }) { Text("清空") } })
    }
}

private fun actionLabel(action: NotificationAction): String = when (action) {
    NotificationAction.KEEP -> "保留原通知"
    NotificationAction.REMIND -> "轻提醒一次"
    NotificationAction.CRITICAL -> "持续强提醒"
}

private fun ruleLabel(rule: NotificationRule): String = buildList {
    if (rule.titleContains.isNotBlank()) add("标题含「${rule.titleContains}」")
    if (rule.bodyContains.isNotBlank()) add("正文含「${rule.bodyContains}」")
    if (rule.bodyExcludes.isNotBlank()) add("正文不含「${rule.bodyExcludes}」")
}.joinToString(" · ").ifBlank { "该 APP 的全部通知" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationRuleDialog(
    initial: NotificationRule,
    managed: List<String>,
    onDismiss: () -> Unit,
    onSave: (NotificationRule) -> Unit
) {
    var selectedPackage by remember(initial.id) { mutableStateOf(initial.packageName) }
    var title by remember(initial.id) { mutableStateOf(initial.titleContains) }
    var body by remember(initial.id) { mutableStateOf(initial.bodyContains) }
    var excludes by remember(initial.id) { mutableStateOf(initial.bodyExcludes) }
    var action by remember(initial.id) { mutableStateOf(initial.action) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重要通知规则") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("来源 APP", style = MaterialTheme.typography.labelLarge)
                Column {
                    managed.forEach { pkg ->
                        FilterChip(selected = selectedPackage == pkg, onClick = { selectedPackage = pkg }, label = { Text(pkg) })
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("标题包含，可留空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it.take(80) }, label = { Text("正文包含，可留空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = excludes, onValueChange = { excludes = it.take(80) }, label = { Text("正文不包含，可留空") }, modifier = Modifier.fillMaxWidth())
                Text("留空全部条件时，匹配该 APP 的所有通知。", style = MaterialTheme.typography.bodySmall)
                Text("命中后的处理", style = MaterialTheme.typography.labelLarge)
                NotificationAction.entries.forEach { choice ->
                    FilterChip(selected = action == choice, onClick = { action = choice }, label = { Text(actionLabel(choice)) })
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = { onSave(initial.copy(packageName = selectedPackage, titleContains = title.trim(), bodyContains = body.trim(), bodyExcludes = excludes.trim(), action = action)) }) { Text("保存") } }
    )
}
