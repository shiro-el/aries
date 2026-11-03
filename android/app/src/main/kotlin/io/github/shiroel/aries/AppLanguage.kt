package io.github.shiroel.aries

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat

/** One persisted locale owner: AppCompat below API 33, Android above. */
object AppLanguage {
    // AppCompat writes its storage asynchronously; surfaces must switch immediately too.
    @Volatile private var inProcessLocales: LocaleListCompat? = null

    fun choose(context: Context, languageTag: String) {
        require(languageTag in listOf("", "en", "ko"))
        val locales = LocaleListCompat.forLanguageTags(languageTag)
        inProcessLocales = locales
        AppCompatDelegate.setApplicationLocales(locales)
        (context.applicationContext as? AriesApplication)?.refreshLanguageSurfaces()
    }

    /** Services, workers and widgets need this on Android 12 and earlier. */
    fun context(context: Context): Context {
        val selected = inProcessLocales
        if (Build.VERSION.SDK_INT >= 33 || selected == null) return ContextCompat.getContextForLanguage(context)
        val effective = if (selected.isEmpty) LocaleManagerCompat.getSystemLocales(context) else selected
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(effective.toLanguageTags()))
        })
    }
}
