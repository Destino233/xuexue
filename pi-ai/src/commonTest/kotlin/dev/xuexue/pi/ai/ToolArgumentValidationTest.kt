package dev.xuexue.pi.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolArgumentValidationTest {
    @Test
    fun coercesPrimitiveValuesAndNestedProperties() {
        val schema =
            schema(
                """
                {
                  "type":"object",
                  "properties":{
                    "count":{"type":"number"},
                    "enabled":{"type":"boolean"},
                    "label":{"type":"string"},
                    "nothing":{"type":"null"},
                    "nested":{"type":"object","properties":{"value":{"type":"integer"}}}
                  },
                  "required":["count","enabled","label","nothing","nested"]
                }
                """,
            )
        val arguments =
            schema(
                """
                {"count":"42","enabled":1,"label":true,"nothing":0,"nested":{"value":"7"}}
                """,
            )

        val result = validateToolArguments(schema, arguments, "coerce")

        assertEquals("42", result.getValue("count").jsonPrimitive.content)
        assertTrue(
            result
                .getValue("enabled")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals("true", result.getValue("label").jsonPrimitive.content)
        assertTrue(result.getValue("nothing") is kotlinx.serialization.json.JsonNull)
        assertEquals(
            "7",
            result
                .getValue("nested")
                .jsonObject
                .getValue("value")
                .jsonPrimitive.content,
        )
        assertEquals("42", arguments.getValue("count").jsonPrimitive.content)
    }

    @Test
    fun preservesMatchingUnionMembersAndCoercesTheFirstValidMember() {
        val schema =
            schema(
                """
                {
                  "type":"object",
                  "properties":{
                    "text":{"type":["number","string"]},
                    "value":{"type":["boolean","number"]}
                  },
                  "required":["text","value"]
                }
                """,
            )

        val result =
            validateToolArguments(
                schema,
                schema("""{"text":"1","value":"1"}"""),
                "union",
            )

        assertEquals("1", result.getValue("text").jsonPrimitive.content)
        assertTrue(result.getValue("text").jsonPrimitive.isString)
        assertEquals("1", result.getValue("value").jsonPrimitive.content)
        assertFalse(result.getValue("value").jsonPrimitive.isString)
    }

    @Test
    fun validatesRequiredAdditionalPropertiesAndFormatsTheOriginalArguments() {
        val schema =
            schema(
                """
                {
                  "type":"object",
                  "properties":{"name":{"type":"string"}},
                  "required":["name"],
                  "additionalProperties":false
                }
                """,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validateToolArguments(schema, schema("""{"extra":true}"""), "inspect")
            }

        assertTrue(error.message.orEmpty().contains("Validation failed for tool \"inspect\""))
        assertTrue(error.message.orEmpty().contains("name: Expected required property"))
        assertTrue(error.message.orEmpty().contains("extra: Unexpected property"))
        assertTrue(error.message.orEmpty().contains("Received arguments:"))
    }

    @Test
    fun supportsAllOfAnyOfAndOneOf() {
        val schema =
            schema(
                """
                {
                  "type":"object",
                  "properties":{
                    "count":{"allOf":[{"type":"number"},{"minimum":3}]},
                    "choice":{"anyOf":[{"type":"integer"},{"type":"boolean"}]},
                    "label":{"oneOf":[{"type":"number"},{"type":"string"}]}
                  },
                  "required":["count","choice","label"]
                }
                """,
            )

        val result =
            validateToolArguments(
                schema,
                schema("""{"count":"4","choice":true,"label":"ok"}"""),
                "combinators",
            )

        assertEquals("4", result.getValue("count").jsonPrimitive.content)
        assertEquals("1", result.getValue("choice").jsonPrimitive.content)
        assertEquals("ok", result.getValue("label").jsonPrimitive.content)
    }

    @Test
    fun rejectsInvalidPrimitiveCoercions() {
        val schema =
            schema(
                """
                {"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}
                """,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                validateToolArguments(schema, schema("""{"enabled":"1"}"""), "strict")
            }

        assertTrue(error.message.orEmpty().contains("enabled"))
    }

    private fun schema(value: String): JsonObject = PiJson.parseToJsonElement(value).jsonObject
}
