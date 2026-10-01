package com.inonvation.lightlife.data

import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class UnlockStepTrackerTest {
    @Test
    fun converterErrorIncludesSkuStepAndReadableReason() = runBlocking {
        val original = JsonDataException("Expected BEGIN_ARRAY but was BEGIN_OBJECT at path $.data")
        val e = runCatching {
            UnlockStepTracker {}.run {
                report("正在获取 SKU")
                throw original
            }
        }.exceptionOrNull() as UnlockException
        assertSame(original, e.cause)
        assertEquals("正在获取 SKU", e.diagnosis.step)
        assertEquals("接口返回的数据格式不兼容", e.diagnosis.primaryReason)
        assertTrue(e.diagnosis.rawError.contains("$.data"))
        println("MODIFIED_PARSE_STEP=${e.diagnosis.step}; REASON=${e.diagnosis.primaryReason}")
    }

    @Test
    fun everyStepUsesTheLastReportedContext() = runBlocking {
        for (step in listOf("正在获取 IMEI", "正在检查积分", "正在开通后付",
                "正在检查位置风控", "正在启动解锁", "正在创建后付订单", "正在查询订单详情")) {
            val reports = mutableListOf<String>()
            val e = runCatching {
                UnlockStepTracker { reports += it }.run {
                    report("正在获取 SKU")
                    report(step)
                    error("测试错误")
                }
            }.exceptionOrNull() as UnlockException
            assertEquals(step, e.diagnosis.step)
            assertEquals(listOf("正在获取 SKU", step), reports)
        }
    }

    @Test
    fun failureBeforeFirstReportUsesPreparationStep() = runBlocking {
        val e = runCatching { UnlockStepTracker {}.run { error("设备缺少 goodsId") } }
            .exceptionOrNull() as UnlockException
        assertEquals("准备解锁", e.diagnosis.step)
    }

    @Test
    fun cancellationIsNotTurnedIntoDeviceFailure() = runBlocking {
        val original = CancellationException("fixture cancelled")
        val e = runCatching { UnlockStepTracker {}.run { throw original } }.exceptionOrNull()
        assertSame(original, e)
    }

    @Test
    fun tokenExpirationIsNotWrapped() = runBlocking {
        val original = TokenExpiredException("token expired")
        val e = runCatching { UnlockStepTracker {}.run { throw original } }.exceptionOrNull()
        assertSame(original, e)
    }

    @Test
    fun existingPollingDiagnosisIsNotReplaced() = runBlocking {
        val original = UnlockException("设备离线",
            DeviceErrorDiagnosis.diagnose(null, "设备离线", "设备状态轮询"))
        val e = runCatching { UnlockStepTracker {}.run { throw original } }.exceptionOrNull()
        assertSame(original, e)
        assertEquals("设备状态轮询", (e as UnlockException).diagnosis.step)
    }

    @Test
    fun businessCodeIsKeptInDiagnosis() = runBlocking {
        val e = runCatching {
            UnlockStepTracker {}.run {
                report("正在获取 SKU")
                throw ApiBusinessException(9999, "版本过低，请升级APP后再使用")
            }
        }.exceptionOrNull() as UnlockException
        assertEquals("服务器要求升级官方客户端", e.diagnosis.primaryReason)
        assertTrue(e.diagnosis.rawError.contains("9999"))
    }

    @Test
    fun httpStatusIsKeptInDiagnosis() = runBlocking {
        val original = HttpException(retrofit2.Response.error<Any>(
            503, "{}".toResponseBody("application/json".toMediaType())))
        val e = runCatching {
            UnlockStepTracker {}.run {
                report("正在获取 IMEI")
                throw original
            }
        }.exceptionOrNull() as UnlockException
        assertEquals("服务端异常", e.diagnosis.primaryReason)
        assertEquals("正在获取 IMEI", e.diagnosis.step)
        assertTrue(e.diagnosis.rawError.contains("503"))
    }

    @Test
    fun rejectedSkuStopsBeforeLaterActionsThroughRetrofit() = runBlocking {
        val paths = mutableListOf<String>()
        val api = fixtureApi("""{"data":{},"code":9999,"msg":"版本过低，请升级APP后再使用"}""", paths)
        var reachedLaterActions = false
        val e = runCatching {
            UnlockStepTracker {}.run {
                report("正在获取 SKU")
                api.goodsid2sku("FIXTURE_GOODS", "FIXTURE_TOKEN").requireData()
                reachedLaterActions = true
            }
        }.exceptionOrNull() as UnlockException
        assertFalse(reachedLaterActions)
        assertEquals(listOf("/goods/normal/skus"), paths)
        assertEquals("正在获取 SKU", e.diagnosis.step)
        assertEquals("服务器要求升级官方客户端", e.diagnosis.primaryReason)
        println("MODIFIED_REJECTED_REQUESTS=${paths.size}; LATER_ACTIONS=$reachedLaterActions")
    }

    @Test
    fun successfulSkuStillReturnsThroughRetrofit() = runBlocking {
        val paths = mutableListOf<String>()
        val api = fixtureApi("""{"code":0,"data":[{"skuId":"FIXTURE_SKU"}]}""", paths)
        val result = UnlockStepTracker {}.run {
            report("正在获取 SKU")
            api.goodsid2sku("FIXTURE_GOODS", "FIXTURE_TOKEN").requireData().first().skuId
        }
        assertEquals("FIXTURE_SKU", result)
        assertEquals(1, paths.size)
        println("MODIFIED_SUCCESS_SKU=$result")
    }

    @Test
    fun unauthorizedSkuThroughRetrofitKeepsTokenException() = runBlocking {
        val api = fixtureApi("""{"data":{},"code":401,"msg":"未登录"}""", mutableListOf())
        val e = runCatching {
            UnlockStepTracker {}.run {
                report("正在获取 SKU")
                api.goodsid2sku("FIXTURE_GOODS", "FIXTURE_TOKEN").requireData()
            }
        }.exceptionOrNull()
        assertTrue(e is TokenExpiredException)
    }

    @Test
    fun callbackFailureStillHasTheNewStep() = runBlocking {
        val e = runCatching {
            UnlockStepTracker { error("fixture callback") }.run { report("正在获取 IMEI") }
        }.exceptionOrNull() as UnlockException
        assertEquals("正在获取 IMEI", e.diagnosis.step)
    }

    private fun fixtureApi(json: String, paths: MutableList<String>): DeviceApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            // Deliberately never call chain.proceed: no real unlock/pay request.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(MoshiProvider.instance))
            .build().create(DeviceApi::class.java)
    }
}
