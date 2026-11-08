package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * JSON as a tree and nothing else.
 *
 * No class here is bound by reflection: a native image has to be told about
 * every class reflection reaches, and a tree needs none of that, so what the
 * server sends is read field by field where it is used.
 */
object Json {
    val mapper: ObjectMapper = ObjectMapper()

    fun obj(): ObjectNode = mapper.createObjectNode()

    fun arr(): ArrayNode = mapper.createArrayNode()

    fun parse(text: String): JsonNode = mapper.readTree(text)

    fun write(node: JsonNode): String = mapper.writeValueAsString(node)

    fun pretty(node: JsonNode): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
}

/** A field as text, or null where it is missing or JSON null. */
fun JsonNode?.str(name: String): String? = this?.get(name)?.takeIf { !it.isNull && !it.isMissingNode }?.let {
    if (it.isTextual) it.textValue() else it.toString()
}

fun JsonNode?.long(name: String): Long? = this?.get(name)?.takeIf { it.isNumber }?.longValue()

fun JsonNode?.double(name: String): Double? = this?.get(name)?.takeIf { it.isNumber }?.doubleValue()

fun JsonNode?.bool(name: String): Boolean? = this?.get(name)?.takeIf { it.isBoolean }?.booleanValue()

fun JsonNode?.list(name: String): List<JsonNode> = this?.get(name)?.takeIf { it.isArray }?.toList() ?: emptyList()

fun JsonNode?.strings(name: String): List<String> = list(name).mapNotNull { if (it.isTextual) it.textValue() else null }
