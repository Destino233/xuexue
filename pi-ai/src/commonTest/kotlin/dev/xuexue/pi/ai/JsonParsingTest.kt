package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsonParsingTest {
    @Test
    fun repairsRawControlCharactersInsideStrings() {
        val malformed = """{"text":"first\tsecond
third"}"""

        val repaired = repairJson(malformed)
        val parsed = parseJsonWithRepair(malformed) as JsonObject

        assertEquals("""{"text":"first\tsecond\nthird"}""", repaired)
        assertEquals("first\tsecond\nthird", parsed.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun doublesBackslashBeforeInvalidEscape() {
        val malformed = """{"path":"A\H"}"""

        assertEquals("""{"path":"A\\H"}""", repairJson(malformed))
        val parsed = parseJsonWithRepair(malformed) as JsonObject
        assertEquals("A\\H", parsed.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun keepsValidEscapesAndUnicodeEscapes() {
        val json = """{"value":"line\n\u4f60\"ok\""}"""

        assertEquals(json, repairJson(json))
    }

    @Test
    fun parsesIncompleteObjectAndString() {
        val parsed = parseStreamingJson("""{"path":"README.md","query":"nee""")

        assertIs<JsonObject>(parsed)
        assertEquals("README.md", parsed.getValue("path").jsonPrimitive.content)
        assertEquals("nee", parsed.getValue("query").jsonPrimitive.content)
    }

    @Test
    fun keepsIncompleteStringValueWhenPartialStringsAreAllowed() {
        val parsed = parseStreamingJson("""{"complete":1,"missing":"""") as JsonObject

        assertEquals("1", parsed.getValue("complete").jsonPrimitive.content)
        assertEquals("", parsed.getValue("missing").jsonPrimitive.content)
    }

    @Test
    fun returnsEmptyObjectForEmptyOrMalformedInput() {
        assertEquals(JsonObject(emptyMap()), parseStreamingJson(null))
        assertEquals(JsonObject(emptyMap()), parseStreamingJson("wrong"))
    }
}
