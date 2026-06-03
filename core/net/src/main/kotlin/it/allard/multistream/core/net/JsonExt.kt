package it.allard.multistream.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Null-tolerant accessors for walking volatile provider JSON without rigid typed models. */
fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun JsonElement?.array(): JsonArray? = this as? JsonArray

fun JsonElement?.string(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.contentOrNull
}

fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

fun JsonElement?.bool(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
}
