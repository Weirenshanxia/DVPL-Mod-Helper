package com.dvpl.modhelper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 轻量本地化助手：持有 application context，任意层（含非 Composable/协程/codec 层）
 * 都可用 L.s(resId) 取当前语言文案。语言切换通过 Activity recreate() 生效。
 */
object AppCtx {
    lateinit var app: Context
}

object L {
    /** 当前语言：system / zh / en / ru */
    const val KEY_LANG = "app_language"

    private var ctx: Context? = null

    fun s(@StringRes id: Int): String = (ctx ?: AppCtx.app).getString(id)

    fun s(@StringRes id: Int, vararg args: Any): String = (ctx ?: AppCtx.app).getString(id, *args)

    // ===== 语言管理 =====

    fun getLanguage(): String =
        AppCtx.app.getSharedPreferences("dvpl_prefs", Context.MODE_PRIVATE)
            .getString(KEY_LANG, "system") ?: "system"

    /** 应用内即时切换：只更新内存 + prefs，由 Compose 重组生效（无 Activity 重建、无黑屏） */
    fun applyLanguage(lang: String) {
        AppCtx.app.getSharedPreferences("dvpl_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
        if (lang == "system") {
            ctx = null
            val sys = AppCtx.app.resources.configuration.locales[0]
            Locale.setDefault(sys)
        } else {
            val loc = Locale.forLanguageTag(lang)
            Locale.setDefault(loc)
            val config = Configuration(AppCtx.app.resources.configuration)
            config.setLocale(loc)
            if (Build.VERSION.SDK_INT >= 24) config.setLocales(LocaleList(loc))
            ctx = AppCtx.app.createConfigurationContext(config)
        }
    }

    /** 根据 prefs 包装 base context 的 Configuration locale（在 attachBaseContext 调用） */
    fun wrap(base: Context): Context {
        AppCtx.app = base.applicationContext
        val lang = base.getSharedPreferences("dvpl_prefs", Context.MODE_PRIVATE)
            .getString(KEY_LANG, "system") ?: "system"
        if (lang == "system") { ctx = null; return base }
        val locales = if (Build.VERSION.SDK_INT >= 33)
            LocaleList(Locale.forLanguageTag(lang))
        else LocaleListCompat.forLanguageTags(lang).let { l ->
            LocaleList(*Array(l.size()) { l.get(it) })
        }
        Locale.setDefault(locales.get(0))
        val config = Configuration(base.resources.configuration)
        config.setLocale(locales.get(0))
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(locales)
        val wrapped = base.createConfigurationContext(config)
        ctx = wrapped
        return wrapped
    }
}
