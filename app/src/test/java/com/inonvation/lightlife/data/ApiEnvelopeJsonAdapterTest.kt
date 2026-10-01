package com.inonvation.lightlife.data

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvelopeJsonAdapterTest {
    private val skuType = Types.newParameterizedType(
        ApiEnvelope::class.java,
        Types.newParameterizedType(List::class.java, SkuData::class.java),
    )
    private val adapter = MoshiProvider.instance.adapter<ApiEnvelope<List<SkuData>>>(skuType)

    private fun parse(json: String) = checkNotNull(adapter.fromJson(json))

    @Test
    fun zeroCodeWithArrayStillParses() {
        assertEquals("SKU_A", parse("""{"code":0,"data":[{"skuId":"SKU_A"}]}""").requireData().first().skuId)
    }

    @Test
    fun code200AndNumericSkuStillParse() {
        assertEquals("123", parse("""{"code":200,"data":[{"skuId":123}]}""").requireData().first().skuId)
    }

    @Test
    fun missingCodeWithValidLegacyArrayStillParses() {
        assertEquals("SKU_A", parse("""{"data":[{"skuId":"SKU_A"}]}""").requireData().first().skuId)
    }

    @Test
    fun objectInRejectedResponsePreservesBusinessMessage() {
        val envelope = parse("""{"code":9999,"msg":"版本过低，请升级APP后再使用","data":{}}""")
        assertNull(envelope.data)
        val e = runCatching { envelope.requireData() }.exceptionOrNull()
        assertTrue(e is ApiBusinessException)
        assertEquals(9999, (e as ApiBusinessException).code)
        assertEquals("版本过低，请升级APP后再使用", e.message)
        println("MODIFIED_REJECTED_SKU=ApiBusinessException(code=${e.code}, message=${e.message})")
    }

    @Test
    fun dataBeforeCodeDoesNotHideBusinessError() {
        val e = runCatching {
            parse("""{"data":{},"message":"设备不可用","code":9999}""").requireData()
        }.exceptionOrNull()
        assertTrue(e is ApiBusinessException)
        assertEquals("设备不可用", e!!.message)
    }

    @Test
    fun validSkuInRejectedResponseIsNotAccepted() {
        val envelope = parse("""{"code":9999,"msg":"设备不可用","data":[{"skuId":"SKU_A"}]}""")
        assertNull(envelope.data)
        assertTrue(runCatching { envelope.requireData() }.exceptionOrNull() is ApiBusinessException)
        println("MODIFIED_REJECTED_WITH_DATA=REJECTED")
    }

    @Test
    fun rejectedScalarPayloadDoesNotMaskTheStatus() {
        for (data in listOf("false", "\"bad\"", "42", "null", "[]")) {
            val envelope = parse("""{"data":$data,"code":500,"msg":"请求失败"}""")
            assertEquals(500, envelope.code)
            assertNull(envelope.data)
            assertTrue(runCatching { envelope.requireData() }.exceptionOrNull() is ApiBusinessException)
        }
    }

    @Test
    fun unauthorizedObjectRequiresRelogin() {
        val e = runCatching {
            parse("""{"data":{},"code":401,"msg":"未登录"}""").requireData()
        }.exceptionOrNull()
        assertTrue(e is TokenExpiredException)
        assertEquals("未登录", e!!.message)
    }

    @Test
    fun forbiddenResponseRequiresRelogin() {
        val e = runCatching {
            parse("""{"code":403,"data":[{"skuId":"SKU_A"}]}""").requireData()
        }.exceptionOrNull()
        assertTrue(e is TokenExpiredException)
    }

    @Test
    fun expiredTokenWithOtherBusinessCodeRequiresRelogin() {
        val e = runCatching {
            parse("""{"code":9999,"msg":"token expired","data":{}}""").requireData()
        }.exceptionOrNull()
        assertTrue(e is TokenExpiredException)
    }

    @Test
    fun successObjectIsNotSilentlyTreatedAsArray() {
        val e = runCatching { parse("""{"code":0,"data":{}}""") }.exceptionOrNull()
        assertTrue(e is JsonDataException)
        assertTrue(e!!.message.orEmpty().contains("path $.data"))
        assertTrue(e.message.orEmpty().contains("业务码=0"))
        println("MODIFIED_SUCCESS_OBJECT=REJECTED_STRICTLY")
    }

    @Test
    fun singleSkuObjectIsNotGuessedAsList() {
        assertTrue(runCatching {
            parse("""{"code":200,"data":{"skuId":"SKU_A"}}""")
        }.exceptionOrNull() is JsonDataException)
    }

    @Test
    fun malformedSuccessKeepsStatusMessageForDiagnosis() {
        val e = runCatching {
            parse("""{"data":{},"code":200,"msg":"接口消息"}""")
        }.exceptionOrNull()
        assertTrue(e is JsonDataException)
        assertTrue(e!!.message.orEmpty().contains("业务码=200"))
        assertTrue(e.message.orEmpty().contains("消息=接口消息"))
        assertTrue(e.cause is JsonDataException)
    }

    @Test
    fun unconfirmedListWrapperIsNotGuessed() {
        assertTrue(runCatching {
            parse("""{"code":0,"data":{"skuList":[{"skuId":"SKU_A"}]}}""")
        }.exceptionOrNull() is JsonDataException)
    }

    @Test
    fun missingCodeWithObjectIsStillRejected() {
        assertTrue(runCatching { parse("""{"data":{}}""") }.exceptionOrNull() is JsonDataException)
    }

    @Test
    fun successfulNullDataStillFailsRequireData() {
        assertTrue(runCatching {
            parse("""{"code":0,"data":null}""").requireData()
        }.isFailure)
    }

    @Test
    fun emptySuccessfulArrayIsNotInventedIntoSku() {
        assertTrue(parse("""{"code":0,"data":[]}""").requireData().isEmpty())
    }

    @Test
    fun normalObjectDtoStillWorks() {
        val type = Types.newParameterizedType(ApiEnvelope::class.java, ImeiData::class.java)
        val envelope = MoshiProvider.instance.adapter<ApiEnvelope<ImeiData>>(type)
            .fromJson("""{"code":0,"data":{"imei":"FIXTURE_IMEI"}}""")!!
        assertEquals("FIXTURE_IMEI", envelope.requireData().imei)
    }

    @Test
    fun failedObjectDtoAlsoPreservesBusinessError() {
        val type = Types.newParameterizedType(ApiEnvelope::class.java, ImeiData::class.java)
        val envelope = MoshiProvider.instance.adapter<ApiEnvelope<ImeiData>>(type)
            .fromJson("""{"data":[],"msg":"设备离线","code":500}""")!!
        assertEquals("设备离线", runCatching { envelope.requireData() }.exceptionOrNull()!!.message)
    }

    @Test
    fun ordinaryListAdapterRemainsStrict() {
        val listType = Types.newParameterizedType(List::class.java, SkuData::class.java)
        val listAdapter = MoshiProvider.instance.adapter<List<SkuData>>(listType)
        assertTrue(runCatching { listAdapter.fromJson("""{"skuId":"SKU_A"}""") }
            .exceptionOrNull() is JsonDataException)
    }

    @Test
    fun successfulEnvelopeRoundTrips() {
        val envelope = ApiEnvelope(code = 200, msg = "成功", data = listOf(SkuData("SKU_A")))
        assertEquals(envelope, adapter.fromJson(adapter.toJson(envelope)))
    }

    @Test
    fun manuallyCreatedFailedEnvelopeCannotReturnData() {
        val e = runCatching {
            ApiEnvelope(code = 500, msg = "设备不可用", data = listOf(SkuData("SKU_A"))).requireData()
        }.exceptionOrNull()
        assertTrue(e is ApiBusinessException)
        assertEquals(500, (e as ApiBusinessException).code)
    }

    @Test
    fun manuallyCreatedExpiredEnvelopeCannotReturnData() {
        assertTrue(runCatching {
            ApiEnvelope(code = 401, data = "present").requireData()
        }.exceptionOrNull() is TokenExpiredException)
    }

    @Test
    fun blankMessageFallsBackToMsg() {
        val e = runCatching {
            parse("""{"code":500,"message":"","msg":"设备离线","data":{}}""").requireData()
        }.exceptionOrNull()
        assertEquals("设备离线", e!!.message)
    }
}
