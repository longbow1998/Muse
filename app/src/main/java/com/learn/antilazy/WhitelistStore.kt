package com.learn.antilazy

import android.content.Context

/** User-selected apps in which reminder timing is paused. */
object WhitelistStore {

    private const val KEY_PACKAGES = "whitelist_packages"

    fun load(context: Context): Set<String> =
        ReminderEngine.prefs(context)
            .getStringSet(KEY_PACKAGES, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it.isNotBlank() && it != context.packageName }

    fun save(context: Context, packages: Set<String>) {
        val normalized = packages.filterTo(mutableSetOf()) {
            it.isNotBlank() && it != context.packageName
        }
        ReminderEngine.prefs(context).edit()
            .putStringSet(KEY_PACKAGES, normalized)
            .apply()
    }
}
