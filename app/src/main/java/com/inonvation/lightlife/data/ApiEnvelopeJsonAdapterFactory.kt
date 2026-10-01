package com.inonvation.lightlife.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Failed responses may contain data={} even when the success DTO is a list.
 * Read the status first, regardless of field order, so a DTO mismatch cannot
 * hide a business error. Successful payloads still use the strict DTO adapter.
 * This factory applies only to DeviceApi's ApiEnvelope, not arbitrary lists.
 */
internal class ApiEnvelopeJsonAdapterFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: Set<Annotation>,
        moshi: Moshi,
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) != ApiEnvelope::class.java || annotations.isNotEmpty()) return null
        val delegate = moshi.nextAdapter<ApiEnvelope<Any?>>(this, type, annotations)
        val codeAdapter = moshi.adapter(Int::class.javaObjectType)
        val stringAdapter = moshi.adapter(String::class.java)

        return object : JsonAdapter<ApiEnvelope<Any?>>() {
            override fun fromJson(reader: JsonReader): ApiEnvelope<Any?>? {
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                    return delegate.fromJson(reader)
                }
                val status = reader.peekJson().use { peek ->
                    var code: Int? = null
                    var msg: String? = null
                    var message: String? = null
                    peek.beginObject()
                    while (peek.hasNext()) {
                        when (peek.nextName()) {
                            "code" -> code = codeAdapter.fromJson(peek)
                            "msg" -> msg = stringAdapter.fromJson(peek)
                            "message" -> message = stringAdapter.fromJson(peek)
                            else -> peek.skipValue()
                        }
                    }
                    peek.endObject()
                    ApiEnvelope<Any?>(code = code, msg = msg, message = message)
                }
                if (status.code != null && status.code != 0 && status.code != 200) {
                    reader.skipValue()
                    return status
                }
                return try {
                    delegate.fromJson(reader)
                } catch (e: JsonDataException) {
                    // Status only: never include data, tokens or request headers.
                    throw JsonDataException(
                        "${e.message}（业务码=${status.code ?: "缺失"}，" +
                            "消息=${status.message ?: status.msg ?: "缺失"}）",
                    ).apply { initCause(e) }
                }
            }

            override fun toJson(writer: JsonWriter, value: ApiEnvelope<Any?>?) {
                delegate.toJson(writer, value)
            }
        }
    }
}
