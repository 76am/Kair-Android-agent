package dev.kair.app.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSchemaTest {

    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `inlines local refs and drops defs`() {
        val input = schema(
            """
            {
              "type": "object",
              "properties": {
                "path": {"${'$'}ref": "#/${'$'}defs/Path"},
                "list": {"type": "array", "items": {"${'$'}ref": "#/${'$'}defs/Path"}}
              },
              "${'$'}defs": {
                "Path": {"type": "string", "description": "file path"}
              }
            }
            """.trimIndent(),
        )

        val output = inlineToolSchema(input)
        val props = output["properties"]!!.jsonObject

        assertEquals("file path", props["path"]!!.jsonObject["description"]!!.toString().trim('"'))
        assertFalse(output.containsKey("\$defs"))
        val items = props["list"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("string", items["type"]!!.toString().trim('"'))
    }

    @Test
    fun `self recursion keeps only the type`() {
        val input = schema(
            """
            {
              "type": "object",
              "properties": {
                "node": {
                  "type": "object",
                  "properties": {"child": {"${'$'}ref": "#/${'$'}defs/Node"}}
                }
              },
              "${'$'}defs": {
                "Node": {
                  "type": "object",
                  "properties": {"child": {"${'$'}ref": "#/${'$'}defs/Node"}}
                }
              }
            }
            """.trimIndent(),
        )

        val output = inlineToolSchema(input)
        assertTrue(output.toString().contains("\"child\""))
        assertFalse(output.toString().contains("\$ref"))
    }

    @Test
    fun `unresolvable refs degrade to their siblings`() {
        val output = inlineToolSchema(schema("""{"type": "object", "properties": {"x": {"${'$'}ref": "https://x/y"}}}"""))
        val x = output["properties"]!!.jsonObject["x"]!!.jsonObject
        assertFalse(x.containsKey("\$ref"))
    }
}
