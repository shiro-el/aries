package io.github.shiroel.aries.wear

import android.content.Context
import android.content.res.Configuration
import io.github.shiroel.aries.weardata.WatchSummary
import java.util.Locale

/** Old phone summaries have no language tag and retain the watch's system language. */
internal fun WatchSummary.localizedContext(context: Context): Context =
    if (languageTag.isBlank()) context
    else context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(languageTag))
    })
