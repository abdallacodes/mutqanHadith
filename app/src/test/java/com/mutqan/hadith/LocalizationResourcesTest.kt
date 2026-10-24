package com.mutqan.hadith

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Arabic `values/strings.xml` is the app's default and the English `values-en/strings.xml` its translation.
 * These read both files as XML, so a string added to one and forgotten in the other fails the build instead of
 * silently showing the wrong language (or a raw fallback) on a user's screen.
 */
class LocalizationResourcesTest {

    private class Entry(val value: String, val translatable: Boolean)

    /** A `<plurals>` resource: its word forms by quantity (`one`, `few`, `other`, ...). */
    private class Plural(val items: Map<String, String>)

    private val res = File("src/main/res")
    private val arabic = load("values/strings.xml")
    private val english = load("values-en/strings.xml")
    private val arabicPlurals = loadPlurals("values/strings.xml")
    private val englishPlurals = loadPlurals("values-en/strings.xml")

    private fun loadPlurals(path: String): Map<String, Plural> {
        val plurals = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, path)).getElementsByTagName("plurals")
        val result = LinkedHashMap<String, Plural>()
        for (i in 0 until plurals.length) {
            val element = plurals.item(i) as Element
            val items = element.getElementsByTagName("item")
            val forms = LinkedHashMap<String, String>()
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                forms[item.getAttribute("quantity")] = item.textContent
            }
            assertTrue("$path declares ${element.getAttribute("name")} twice", result.put(element.getAttribute("name"), Plural(forms)) == null)
        }
        return result
    }

    private fun load(path: String): Map<String, Entry> {
        val file = File(res, path)
        assertTrue("$file is missing", file.isFile)
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val entries = LinkedHashMap<String, Entry>()
        for (i in 0 until strings.length) {
            val element = strings.item(i) as Element
            val name = element.getAttribute("name")
            val entry = Entry(element.textContent, element.getAttribute("translatable") != "false")
            assertTrue("$path declares $name twice", entries.put(name, entry) == null)
        }
        return entries
    }

    private fun Map<String, Entry>.translatable() = filterValues { it.translatable }

    /** `%1$d`, `%2$s`: what a translation must keep, in whatever order its grammar wants. */
    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.sorted().toList()

    private val arabicLetters = Regex("[\\u0600-\\u06FF]")
    private val latinLetters = Regex("[A-Za-z]")

    private fun withoutPlaceholders(text: String) = text.replace(Regex("""%\d\$[sd]"""), "")

    @Test
    fun everyArabicStringHasAnEnglishTranslation() {
        val missing = arabic.translatable().keys - english.keys
        assertTrue("values-en/strings.xml has no translation for: $missing", missing.isEmpty())
    }

    @Test
    fun englishHasNoStringsTheDefaultLacks() {
        val orphans = english.keys - arabic.translatable().keys
        assertTrue("values-en/strings.xml translates strings that do not exist or are not translatable: $orphans", orphans.isEmpty())
    }

    @Test
    fun everyArabicPluralHasAnEnglishTranslationAndTheOtherWayRound() {
        assertEquals(arabicPlurals.keys, englishPlurals.keys)
    }

    @Test
    fun pluralsHaveTheWordFormsOfTheirLanguage() {
        // Arabic tells zero, one, two, a few, many and the rest apart; English has one and other.
        arabicPlurals.forEach { (name, plural) ->
            assertEquals("forms of $name", setOf("zero", "one", "two", "few", "many", "other"), plural.items.keys)
        }
        englishPlurals.forEach { (name, plural) ->
            assertEquals("forms of $name", setOf("one", "other"), plural.items.keys)
        }
    }

    @Test
    fun pluralsKeepTheirPlaceholdersAndTheirScript() {
        englishPlurals.forEach { (name, plural) ->
            val expected = arabicPlurals.getValue(name).items.getValue("other")
            plural.items.forEach { (quantity, text) ->
                assertEquals("placeholders of $name/$quantity", placeholders(expected), placeholders(text))
                assertTrue("$name/$quantity is not English: $text", !arabicLetters.containsMatchIn(text))
            }
        }
        arabicPlurals.forEach { (name, plural) ->
            plural.items.forEach { (quantity, text) ->
                assertTrue("$name/$quantity is not Arabic: $text", !latinLetters.containsMatchIn(withoutPlaceholders(text)))
                // The forms that stand for a number in particular carry it; "zero", "one" and "two" may say it in words.
                if (quantity in setOf("few", "many", "other")) assertTrue("$name/$quantity lacks its number: $text", "%1\$d" in text)
            }
        }
    }

    @Test
    fun noStringIsBlank() {
        (arabic.entries + english.entries).forEach { (name, entry) ->
            assertTrue("$name is blank", entry.value.isNotBlank())
        }
    }

    @Test
    fun translationsKeepTheirPlaceholders() {
        english.forEach { (name, entry) ->
            assertEquals("placeholders of $name", placeholders(arabic.getValue(name).value), placeholders(entry.value))
        }
    }

    @Test
    fun englishTextCarriesNoArabicExceptTheBilingualLanguageRow() {
        english.filterKeys { it != "settings_language" }.forEach { (name, entry) ->
            assertTrue("$name is not English: ${entry.value}", !arabicLetters.containsMatchIn(entry.value))
        }
        val row = english.getValue("settings_language").value
        assertTrue("the language row names Arabic in Arabic so it can be found", arabicLetters.containsMatchIn(row))
    }

    @Test
    fun arabicTextCarriesNoEnglishExceptJsonAndTheBilingualLanguageRow() {
        arabic.translatable().filterKeys { it != "settings_language" }.forEach { (name, entry) ->
            val text = withoutPlaceholders(entry.value).replace("JSON", "")
            assertTrue("$name is not Arabic: ${entry.value}", !latinLetters.containsMatchIn(text))
        }
        val row = arabic.getValue("settings_language").value
        assertTrue("the language row names English in English so it can be found", latinLetters.containsMatchIn(row))
    }

    @Test
    fun percentSignsFollowTheLanguage() {
        arabic.filterValues { "٪" in it.value }.keys.forEach { name ->
            val translated = english.getValue(name).value
            assertTrue("$name should use a plain percent sign: $translated", "٪" !in translated && "%%" in translated)
        }
    }

    @Test
    fun theTiersTheSeedStoresAreWordedInArabicExactlyAsTheDatasetStoresThem() {
        val seed = File("src/main/assets/seed_hadiths.json").readText(Charsets.UTF_8)
        val stored = Regex(""""tier"\s*:\s*"([^"]+)"""").findAll(seed).map { it.groupValues[1] }.toSet()
        val worded = arabic.filterKeys { it.startsWith("tier_") }.values.map { it.value }.toSet()
        assertTrue("the seed has no tiers to compare with", stored.isNotEmpty())
        assertTrue("every tier the seed stores is worded: ${stored - worded}", worded.containsAll(stored))
    }

    @Test
    fun listSeparatorKeepsItsTrailingSpace() {
        assertEquals("، ", arabic.getValue("list_separator").value.trim('"'))
        assertEquals(", ", english.getValue("list_separator").value.trim('"'))
    }
}
