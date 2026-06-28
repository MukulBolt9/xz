package com.kakao.taxi.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Stealth mode: toggles the enabled state of the LauncherAlias activity-alias.
 *
 * MainActivity itself is NEVER disabled — it has no LAUNCHER category, so
 * disabling the alias removes the icon from both the home screen AND app drawer
 * on all launchers. After toggling the component state we broadcast
 * ACTION_PACKAGE_CHANGED so the launcher refreshes its package cache immediately.
 * Samsung One UI and other launchers cache the package list and won't react until
 * they receive this broadcast — that's why the icon remained visible before.
 */
object StealthHelper {

    private const val ALIAS_CLASS = "com.kakao.taxi.LauncherAlias"

    /** true = icon hidden (stealth ON). false = icon visible (stealth OFF). */
    fun setIconHidden(context: Context, hidden: Boolean) {
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        val newState = if (hidden) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            pm.setComponentEnabledSetting(alias, newState, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {
            // Some OEM ROMs restrict this — fail silently, QS tile still works
        }
        // Force every launcher (Samsung One UI, AOSP, Pixel) to flush its package
        // cache NOW. Without this broadcast, the icon stays in the drawer even
        // though the component is disabled — launchers only re-query on their own
        // schedule. Sending ACTION_PACKAGE_CHANGED with the component list causes
        // them to immediately refresh.
        try {
            context.sendBroadcast(
                Intent(Intent.ACTION_PACKAGE_CHANGED).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, arrayOf(ALIAS_CLASS))
                    putExtra(Intent.EXTRA_DONT_KILL_APP, true)
                }
            )
        } catch (_: Exception) {}
    }

    fun isIconHidden(context: Context): Boolean {
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        return try {
            pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (_: Exception) {
            false
        }
    }
}
