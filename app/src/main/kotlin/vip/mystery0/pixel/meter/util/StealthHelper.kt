package com.kakao.taxi.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Stealth mode: toggles the enabled state of the LauncherAlias activity-alias.
 *
 * MainActivity itself is NEVER disabled — it has no LAUNCHER category, so
 * disabling the alias just removes the icon from the home screen / app
 * drawer while leaving the app fully launchable via the QS tile
 * ([com.kakao.taxi.service.tile.StealthAccessTileService]) or any other
 * component that targets MainActivity directly (deep links, widgets, etc).
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
            // Some OEM launchers/ROMs restrict this — fail silently, tile still works
        }
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
