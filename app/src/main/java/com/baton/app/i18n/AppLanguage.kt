package com.baton.app.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 앱 화면 언어를 바꾼다. Android 13+ 앱별 언어 API를 사용 (시스템 설정의 '앱 언어'와 같은 기능).
 * Pleos 기기에서 Resources 언어 지정이 무시되는 문제를 피하는 공식 경로.
 */
object AppLanguage {
    fun current(context: Context): Locale = context.resources.configuration.locales[0]

    fun set(context: Context, locale: Locale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(locale)
        }
    }
}
