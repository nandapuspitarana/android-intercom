package com.intercom.video.twoway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** FR-023: every user-facing string exists in both English and Indonesian, with the same placeholders. */
class StringResourcesTest {
    private fun load(dir: String): Map<String, String> {
        val file = listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    private val en = load("values")
    private val id = load("values-in")

    private fun placeholders(s: String) = Regex("""%\d\$[sd]|%[sd]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun bothLanguagesHaveTheSameKeys() {
        assertEquals(emptySet<String>(), en.keys - id.keys, "missing from values-in/strings.xml")
        assertEquals(emptySet<String>(), id.keys - en.keys, "missing from values/strings.xml")
        assertTrue(en.size > 100, "sanity: found ${en.size} strings")
    }

    @Test
    fun noStringIsEmpty() {
        assertEquals(emptyList<String>(), en.filterValues { it.isBlank() }.keys.toList())
        assertEquals(emptyList<String>(), id.filterValues { it.isBlank() }.keys.toList())
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        for (key in en.keys.intersect(id.keys)) {
            assertEquals(placeholders(en.getValue(key)), placeholders(id.getValue(key)), "placeholders of '$key' differ between languages")
        }
    }

    @Test
    fun translatedStringsAreActuallyTranslatedUnlessTheyAreNamesOrNumbers() {
        val sameAllowed =
            setOf("app_name", "call_duration", "route_earpiece", "route_speaker", "route_bluetooth", "language_en", "language_in", "outcome_failed")
        val untranslated = en.keys.intersect(id.keys).filter { en[it] == id[it] && it !in sameAllowed && en.getValue(it).length > 12 }
        assertEquals(emptyList<String>(), untranslated, "these look untranslated")
    }

    @Test
    fun xmlApostrophesAreEscapedSoTheResourcesCompile() {
        val raw = listOf("values", "values-in").map { dir ->
            listOf("src/main/res/$dir/strings.xml", "app/src/main/res/$dir/strings.xml").map(::File).first { it.exists() }.readText()
        }
        for (text in raw) {
            Regex(">([^<]*)</string>").findAll(text).forEach { m ->
                val body = m.groupValues[1]
                assertTrue(!Regex("""(?<!\\)'""").containsMatchIn(body), "unescaped apostrophe in: ${body.take(60)}")
            }
        }
    }
}
