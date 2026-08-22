package com.learn.antilazy

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换：system（跟随系统，中文系统显示中文，其余英文）/ zh / en。
 * 零依赖实现：在 attachBaseContext 中包装 Context 的 Configuration。
 */
@SuppressLint("AppBundleLocaleChanges") // APK 直发（非 AAB），无需 Play 语言拆分。
object LanguageUtils {

    const val FOLLOW_SYSTEM = "system"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    fun selected(context: Context): String =
        ReminderEngine.prefs(context)
            .getString(RuleStore.KEY_APP_LANGUAGE, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM

    fun select(context: Context, language: String) {
        ReminderEngine.prefs(context).edit()
            .putString(RuleStore.KEY_APP_LANGUAGE, language)
            .apply()
    }

    /**
     * 把 base 包装为所选语言的 Context。
     * 跟随系统时无需包装：默认资源为英文、values-zh 为中文，
     * 系统语言解析会自动选择正确目录。
     */
    fun wrap(base: Context): Context = when (selected(base)) {
        LANG_ZH -> wrapWithTag(base, "zh-CN")
        LANG_EN -> wrapWithTag(base, "en")
        else -> base
    }

    private fun wrapWithTag(base: Context, languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
