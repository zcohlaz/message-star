package com.messagestar.app.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

object DeviceSettingsHelper {
    fun manufacturerName(): String = Build.MANUFACTURER.ifBlank { Build.BRAND }

    fun openAutoStartSettings(context: Context): Boolean {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        val candidates = vendorComponents(vendor).map { (packageName, className) ->
            Intent().setComponent(ComponentName(packageName, className))
        } + Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )

        for (target in candidates) {
            val opened = runCatching {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(target)
                true
            }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }

    private fun vendorComponents(vendor: String): List<Pair<String, String>> = when {
        vendor.contains("vivo") || vendor.contains("iqoo") -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            "com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
        )
        vendor.contains("oppo") || vendor.contains("oneplus") || vendor.contains("realme") -> listOf(
            "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        )
        vendor.contains("xiaomi") || vendor.contains("redmi") || vendor.contains("poco") -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.powercenter.PowerSettings"
        )
        vendor.contains("huawei") -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity"
        )
        vendor.contains("honor") -> listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )
        vendor.contains("samsung") -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        vendor.contains("meizu") -> listOf(
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity"
        )
        else -> emptyList()
    }
}
