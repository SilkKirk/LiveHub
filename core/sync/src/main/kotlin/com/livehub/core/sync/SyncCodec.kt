package com.livehub.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Wire codec for [SyncRecord] payloads.
 *
 * Records are encoded as a JSON array of objects so the format stays readable in the
 * sync log and tolerant to additive changes. Unknown `value` fields are preserved by
 * the per-scope serializers, not here.
 */
object SyncCodec {
  val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
  }

  /** Encodes a record whose `value` is already converted to a JSON element. */
  fun encodeRecords(records: List<SyncRecord<JsonObject>>): String {
    val array = kotlinx.serialization.json.JsonArray(
      records.map { record ->
        buildJsonObject {
          put("key", record.key)
          put("deleted", record.deleted)
          put("hlc", record.hlc.encode())
          put("deviceId", record.deviceId)
          put("updatedAt", record.updatedAt)
          record.value?.let { put("value", it) }
        }
      },
    )
    return array.toString()
  }

  fun decodeRecords(payload: String): List<SyncRecord<JsonObject>> {
    if (payload.isBlank()) return emptyList()
    val array = json.parseToJsonElement(payload).jsonArray
    return array.mapNotNull { element ->
      val obj = element.jsonObject
      val key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null
      SyncRecord(
        key = key,
        value = obj["value"] as? JsonObject,
        deleted = obj["deleted"]?.jsonPrimitive?.boolean ?: false,
        hlc = Hlc.decode(obj["hlc"]?.jsonPrimitive?.content),
        deviceId = obj["deviceId"]?.jsonPrimitive?.long ?: 0,
        updatedAt = obj["updatedAt"]?.jsonPrimitive?.long ?: 0,
      )
    }
  }

  /** Serialises a payload for transport. */
  fun encodePayload(payload: SyncPayload): String = json.encodeToString(SyncPayload.serializer(), payload)

  fun decodePayload(raw: String): SyncPayload =
    json.decodeFromString(SyncPayload.serializer(), raw)

  /** Builds the JSON document sent for a scope. */
  fun buildPayload(
    scope: SyncScope,
    records: List<SyncRecord<JsonObject>>,
    deviceId: Long,
    sequence: Long,
    now: Long,
  ): SyncPayload = SyncPayload(
    scope = scope.wireName,
    schema = SyncPayload.SCHEMA,
    sourceDeviceId = deviceId,
    sequence = sequence,
    createdAt = now,
    entries = encodeRecords(records),
  )

  /** Convenience builder for a scalar string value. */
  fun stringValue(value: String): JsonObject = buildJsonObject { put("v", value) }

  fun intValue(value: Int): JsonObject = buildJsonObject { put("v", value) }

  fun longValue(value: Long): JsonObject = buildJsonObject { put("v", value) }

  fun flagValue(value: Boolean): JsonObject = buildJsonObject { put("v", value) }

  fun stringListValue(values: List<String>): JsonObject = buildJsonObject {
    putJsonArray("v") { values.forEach { add(JsonPrimitive(it)) } }
  }

  fun readString(obj: JsonObject?): String? = obj?.get("v")?.jsonPrimitive?.content

  fun readInt(obj: JsonObject?): Int? = obj?.get("v")?.jsonPrimitive?.content?.toIntOrNull()

  fun readLong(obj: JsonObject?): Long? = obj?.get("v")?.jsonPrimitive?.content?.toLongOrNull()

  fun readFlag(obj: JsonObject?): Boolean? = obj?.get("v")?.jsonPrimitive?.content?.toBooleanStrictOrNull()

  fun readStringList(obj: JsonObject?): List<String> =
    obj?.get("v")?.jsonArray?.mapNotNull { it.jsonPrimitive.content }.orEmpty()
}
