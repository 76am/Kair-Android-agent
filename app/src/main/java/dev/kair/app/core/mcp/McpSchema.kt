package dev.kair.app.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 MCP 工具 `inputSchema` 里的文档内引用（`#/$defs/...`）就地展开。
 *
 * 远端工具常用 `$defs` + `$ref` 复用结构，而部分 provider（如 Gemini）不接受
 * 悬空 / 外链引用，整条请求会被拒。展开后删掉 `$defs` / `definitions`，既避免
 * dangling ref，也少传一份重复定义。
 */
internal fun inlineToolSchema(schema: JsonObject): JsonObject {
    val inlined = inlineNode(schema, schema, emptySet()) as? JsonObject ?: schema
    return JsonObject(inlined - "\$defs" - "definitions")
}

private val SUB_SCHEMA_KEYWORDS = setOf(
    "items", "prefixItems", "additionalItems", "unevaluatedItems", "contains",
    "additionalProperties", "unevaluatedProperties", "propertyNames",
    "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "contentSchema",
)

private val SUB_SCHEMA_MAP_KEYWORDS = setOf(
    "properties", "patternProperties", "dependentSchemas", "\$defs", "definitions",
)

private fun inlineNode(node: JsonElement, root: JsonObject, resolving: Set<String>): JsonElement {
    val obj = node as? JsonObject ?: return node
    val ref = (obj["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val withoutRef = if (ref != null) obj - "\$ref" else obj
    val children = withoutRef.mapValues { (key, value) ->
        when {
            key in SUB_SCHEMA_KEYWORDS && value is JsonArray ->
                JsonArray(value.map { inlineNode(it, root, resolving) })

            key in SUB_SCHEMA_KEYWORDS -> inlineNode(value, root, resolving)
            key in SUB_SCHEMA_MAP_KEYWORDS && value is JsonObject ->
                JsonObject(value.mapValues { (_, v) -> inlineNode(v, root, resolving) })

            else -> value
        }
    }
    if (ref == null) return JsonObject(children)

    val target = root.resolvePointer(ref) as? JsonObject ?: return JsonObject(children)
    val expanded = if (ref in resolving) {
        // 递归引用无法完全展开：保留类型，避免无限递归。
        JsonObject(target.filterKeys { it == "type" })
    } else {
        inlineNode(target, root, resolving + ref) as? JsonObject ?: target
    }
    // 与 $ref 同级的字段覆盖被引用定义里的同名键。
    return JsonObject(expanded + children)
}

private fun JsonObject.resolvePointer(ref: String): JsonElement? {
    if (!ref.startsWith("#")) return null
    val tokens = ref.removePrefix("#").split("/").drop(1)
        .map { it.replace("~1", "/").replace("~0", "~") }
    return tokens.fold<String, JsonElement?>(this) { current, token ->
        when (current) {
            is JsonObject -> current[token]
            is JsonArray -> token.toIntOrNull()?.let(current::getOrNull)
            else -> null
        }
    }
}
