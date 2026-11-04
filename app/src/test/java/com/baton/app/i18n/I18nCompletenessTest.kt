package com.baton.app.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 다국어 문구 검사 (I18N-xx). 번역이 하나라도 빠지거나 형식 자리표시자(%1$s 등)가 다르면
 * 그 언어 사용자에게만 앱이 죽거나 영어가 섞여 나온다. 빌드 전에 잡는다.
 */
class I18nCompletenessTest {

    private val res = File("src/main/res")
    private val locales = listOf("values", "values-ko", "values-ja", "values-zh-rCN")

    private fun strings(dir: String): Map<String, String> {
        val xml = File(res, "$dir/strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(s: String) = Regex("""%(\d+)\$([sd])""").findAll(s).map { it.value }.sorted().toList()

    @Test fun `I18N-01 모든 언어에 같은 문구 키가 있다`() {
        val base = strings("values").keys
        for (l in locales.drop(1)) {
            val keys = strings(l).keys
            assertTrue("$l 에 빠진 키: ${base - keys}", (base - keys).isEmpty())
            assertTrue("$l 에만 있는 키: ${keys - base}", (keys - base).isEmpty())
        }
    }

    @Test fun `I18N-02 형식 자리표시자가 언어마다 일치한다`() {
        val base = strings("values")
        for (l in locales.drop(1)) {
            val other = strings(l)
            for ((k, v) in base) {
                val o = other[k] ?: continue
                assertTrue("$l/$k: ${placeholders(v)} ≠ ${placeholders(o)}", placeholders(v) == placeholders(o))
            }
        }
    }

    @Test fun `I18N-03 빈 번역이 없다`() {
        for (l in locales) for ((k, v) in strings(l)) assertTrue("$l/$k 가 비어 있음", v.isNotBlank())
    }

    @Test fun `I18N-04 화면 언어 목록과 리소스 폴더가 일치한다`() {
        val cfg = File(res, "xml/locales_config.xml").readText()
        for (tag in listOf("ko", "en", "ja", "zh-CN")) assertTrue("locales_config 에 $tag 없음", cfg.contains("\"$tag\""))
    }

    @Test fun `I18N-05 표절 방지 금칙어가 문구에 없다`() {
        val banned = listOf("최초", "세계 유일", "메시지를 남기", "메모장")
        for (l in locales) for ((k, v) in strings(l)) for (b in banned) assertTrue("$l/$k 에 금칙어 '$b'", !v.contains(b))
    }
}
