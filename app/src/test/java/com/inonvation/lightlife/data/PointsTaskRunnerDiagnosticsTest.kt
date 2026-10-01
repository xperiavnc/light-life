package com.inonvation.lightlife.data

import com.squareup.moshi.Types
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the actual runner through a short-circuiting OkHttp interceptor.
 * No request reaches the network, and every response is a local fixture.
 */
class PointsTaskRunnerDiagnosticsTest {
    @Test
    fun rejectedResponseStopsAfterOneAttemptInsteadOfTen() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task(limit = 10)),
            completion = mapOf("code" to 9999, "msg" to "成功", "data" to null),
        )
        fixture.run()
        assertEquals(1, fixture.completionRequests())
        assertTrue(fixture.logs.any { it.contains("code=9999，data=null，msg=成功") })
        assertTrue(fixture.logs.any { it.contains("taskType=0，分支=普通任务") })
        assertTrue(fixture.logs.any { it.contains("task-diag-v1") })
        assertTrue(fixture.logs.any { it.contains("接口已受理/已记录：0/1 项") })
        println("REJECTED_RETRY_COUNT=${fixture.completionRequests()} (limit=10)")
    }

    @Test
    fun versionRejectionStopsBeforeSubmittingTheNextTask() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task("TASK_A"), task("TASK_B")),
            completion = mapOf("code" to 9999, "msg" to "版本过低，请升级APP后再使用"),
        )
        fixture.run()
        assertEquals(1, fixture.completionRequests())
        assertFalse(fixture.logs.any { it == "开始执行：TASK_B（0/1）" })
        assertTrue(fixture.logs.any { it.contains("服务器要求升级官方客户端，本轮停止任务提交") })
        println("UPGRADE_SUBMISSIONS=${fixture.completionRequests()} (catalogue=2)")
    }

    @Test
    fun falseDataDoesNotMarkTaskCompletedOrKeepRetrying() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task(limit = 10)),
            completion = mapOf("code" to 200, "msg" to "成功", "data" to false),
        )
        fixture.run()
        assertEquals(1, fixture.completionRequests())
        assertTrue(fixture.logs.any { it.contains("data=boolean(false)") })
        assertTrue(fixture.logs.any { it.contains("接口已受理/已记录：0/1 项") })
        println("FALSE_DATA_COMPLETED=0; SUBMISSIONS=${fixture.completionRequests()}")
    }

    @Test
    fun subtaskFailureStopsTheRestOfTheSubtasks() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task() + ("subtaskList" to listOf(
                mapOf("subtaskCode" to "SUB_A"),
                mapOf("subtaskCode" to "SUB_B"),
            ))),
            completion = mapOf("code" to 9999, "msg" to "成功"),
        )
        fixture.run()
        assertEquals(1, fixture.completionRequests())
    }

    @Test
    fun missingAdConfigurationNeverFallsBackToOrdinaryCompletion() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task(type = 1), task("TASK_B", type = 2)),
        )
        fixture.run()
        assertEquals(0, fixture.completionRequests())
        assertFalse(fixture.paths.contains("/task/getTaskReward"))
        assertTrue(fixture.logs.any { it.contains("缺少官方广告位，跳过领奖") })
        println("MISSING_AD_CONFIG_SUBMISSIONS=0; REWARD_CLAIMS=0")
    }

    @Test
    fun unavailableBalanceIsNotReportedAsVerifiedZeroPoints() = runBlocking {
        val fixture = Fixture(
            tasks = emptyList(),
            balance = mapOf("code" to 500, "data" to mapOf("integral" to 21)),
        )
        fixture.run()
        assertTrue(fixture.logs.any { it == "任务流程结束，当前积分：-（本轮 未核验）" })
    }

    @Test
    fun finalBalanceFailureDoesNotTurnCachedBalanceIntoVerifiedZero() = runBlocking {
        val fixture = Fixture(
            tasks = emptyList(),
            finalBalance = mapOf("code" to 500, "data" to mapOf("integral" to 21)),
        )
        fixture.run()
        assertTrue(fixture.logs.any { it == "任务流程结束，当前积分：未核验，上次读取 21（本轮 未核验）" })
        println("FINAL_BALANCE_FAILURE_EARNED=未核验 (cached=21)")
    }

    @Test
    fun signinUpgradeStopsBeforeLoadingTasks() = runBlocking {
        val fixture = Fixture(
            tasks = listOf(task()),
            signin = mapOf("code" to 200, "msg" to "版本过低，请升级APP后再使用"),
        )
        fixture.run()
        assertFalse(fixture.paths.contains("/task/list"))
        assertEquals(0, fixture.completionRequests())
    }

    private fun task(
        code: String = "TASK_A",
        limit: Int = 1,
        type: Int = 0,
    ): Map<String, Any?> = mapOf(
        "taskCode" to code,
        "title" to code,
        "taskType" to type,
        "completedStatus" to 0,
        "completedFreq" to 0,
        "dailyTaskLimit" to limit,
    )

    private class Fixture(
        private val tasks: List<Map<String, Any?>>,
        private val completion: Map<String, Any?> = mapOf("code" to 500, "msg" to "fixture rejection"),
        private val balance: Map<String, Any?> = mapOf("code" to 200, "data" to mapOf("integral" to 21)),
        private val finalBalance: Map<String, Any?> = balance,
        private val signin: Map<String, Any?> = mapOf("code" to 33001, "msg" to "今天已经签到过"),
    ) {
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val logs = mutableListOf<String>()

        suspend fun run() {
            var balanceReads = 0
            val adapter = MoshiProvider.instance.adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                paths.add(path)
                val envelope = when (path) {
                    "/user/info" -> mapOf("code" to 200, "data" to mapOf("userName" to "FIXTURE_USER"))
                    "/user/balance" -> if (balanceReads++ == 0) balance else finalBalance
                    "/signin/signInActList" -> mapOf("code" to 200, "data" to mapOf("id" to "FIXTURE_ACTIVITY"))
                    "/signin/doUserSignIn" -> signin
                    "/shielding/query" -> mapOf("code" to 200, "msg" to "成功")
                    "/task/list" -> mapOf("code" to 200, "data" to mapOf("items" to tasks))
                    "/task/completed" -> completion
                    else -> error("Unexpected request: $path; fixture never uses the network")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("Fixture")
                    .body(adapter.serializeNulls().toJson(envelope).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
            PointsTaskRunner(tokenProvider = { "FIXTURE_TOKEN" }, client = client)
                .run("FIXTURE_UA") { logs.add(it) }
        }

        fun completionRequests(): Int = paths.count { it == "/task/completed" }
    }
}
