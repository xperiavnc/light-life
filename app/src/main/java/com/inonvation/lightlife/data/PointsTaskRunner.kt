package com.inonvation.lightlife.data

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.security.MessageDigest
import kotlin.jvm.Volatile

class TaskCancelledException : Exception()

/**
 * Server-driven points task runner.
 *
 * The old implementation used fixed task codes and treated every task as
 * task/completed. The current official client first reads task/list, then uses
 * task/completed for ordinary tasks and task/getTaskReward for reward-video
 * tasks. This runner follows that current response shape instead of keeping a
 * stale task-code catalogue.
 */
class PointsTaskRunner(
    private val tokenProvider: () -> String?,
    private val context: Context? = null,
    private val pointsStatsStore: PointsStatsStore? = null,
) {
    @Volatile
    var cancelled = false

    @Volatile
    var paused = false

    @Volatile
    var randomDelay = false

    private var debugLog: DebugLogStore? = null

    fun setDebugLog(log: DebugLogStore?) {
        debugLog = log
    }

    /** stage / current / total, consumed by the foreground service. */
    var onProgress: ((stage: String, current: Int, total: Int) -> Unit)? = null

    private val client = HttpClientProvider.client
    private val jsonAdapter: JsonAdapter<Map<String, Any?>> = MoshiProvider.instance
        .adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    private val statePrefs by lazy {
        context?.getSharedPreferences("ad_video_state", Context.MODE_PRIVATE)
    }

    private fun today(): String = DateUtils.today()

    private fun getState(key: String): Boolean {
        val prefs = statePrefs ?: return false
        return prefs.getString("${key}_date", "") == today() && prefs.getBoolean(key, false)
    }

    private fun setState(key: String, value: Boolean) {
        val prefs = statePrefs ?: return
        prefs.edit()
            .putBoolean(key, value)
            .putString("${key}_date", today())
            .apply()
    }

    private fun checkCancelled() {
        if (cancelled) throw TaskCancelledException()
    }

    private suspend fun waitIfPaused(log: (suspend (String) -> Unit)? = null) {
        if (paused) log?.invoke("\u200B⏸ 任务已暂停，等待继续...")
        while (paused) {
            delay(1000)
            checkCancelled()
        }
    }

    private suspend fun maybeDelay(log: (suspend (String) -> Unit)? = null) {
        val delayMs = if (randomDelay) {
            2000L + (Math.random() * 4000).toLong()
        } else {
            1500L
        }
        if (randomDelay) log?.invoke("随机延迟 ${(delayMs / 1000).toInt()}秒")
        delay(delayMs)
    }

    suspend fun run(userAgent: String, log: suspend (String) -> Unit) {
        checkCancelled()
        waitIfPaused(log)
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: error("请先在我的页面登录")

        val user = request(
            url = "https://userapi.qiekj.com/user/info",
            token = token,
            userAgent = userAgent,
            fields = emptyMap(),
        )
        val userName = user.dataMap()["userName"]?.toString()
        log(if (userName.isNullOrBlank()) "当前账号未设置昵称" else "当前账号：$userName")

        var lastBalance = balance(token, userAgent)
        val initialBalance = lastBalance
        log("任务前积分：${lastBalance ?: "-"}")

        if (!getState("signin_done")) {
            val activityId = resolveSignInActivityId(token, userAgent, log)
            signIn(token, userAgent, activityId, log)
            lastBalance = logBalanceDelta("签到", token, userAgent, lastBalance, log)
        } else {
            log("签到：已跳过")
        }

        checkCancelled()
        shieldingQuery(token, userAgent, log)
        checkCancelled()
        lastBalance = runOfficialTaskList(token, userAgent, log, lastBalance)

        val after = balance(token, userAgent) ?: lastBalance
        val earned = if (after != null && initialBalance != null) after - initialBalance else null
        log("任务完成，当前积分：${after ?: "-"}（今日 +${earned ?: 0}）")
        if (earned != null && earned > 0) {
            pointsStatsStore?.addTodayEarned(earned)
        }
    }

    private suspend fun resolveSignInActivityId(
        token: String,
        ua: String,
        log: suspend (String) -> Unit,
    ): String {
        val response = runCatching {
            request(
                url = "https://userapi.qiekj.com/signin/signInActList",
                token = token,
                userAgent = ua,
                fields = emptyMap(),
            )
        }.getOrElse {
            log("读取签到活动失败，使用兼容活动号：$DEFAULT_SIGN_IN_ACTIVITY_ID")
            return DEFAULT_SIGN_IN_ACTIVITY_ID
        }
        val activityId = response.dataMap()["id"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: response.dataMap()["activityId"]?.toString()
                ?.takeIf { it.isNotBlank() }
        if (response.isOk() && activityId != null) {
            return activityId
        }
        log("签到活动未返回有效活动号，使用兼容活动号：$DEFAULT_SIGN_IN_ACTIVITY_ID")
        return DEFAULT_SIGN_IN_ACTIVITY_ID
    }

    private suspend fun signIn(
        token: String,
        ua: String,
        activityId: String,
        log: suspend (String) -> Unit,
    ) {
        log("开始执行签到...")
        val res = request(
            url = "https://userapi.qiekj.com/signin/doUserSignIn",
            token = token,
            userAgent = ua,
            fields = mapOf("activityId" to activityId),
        )
        when {
            res.isOk() -> {
                log("签到成功，当前积分：${res.dataMap()["totalIntegral"] ?: "-"}")
                setState("signin_done", true)
                onProgress?.invoke("signin", 1, 1)
            }
            res.codeInt() == 33001 -> {
                log("今天已经签到过")
                setState("signin_done", true)
                onProgress?.invoke("signin", 1, 1)
            }
            else -> log("签到失败：${res.messageText()}")
        }
    }

    private suspend fun shieldingQuery(
        token: String,
        ua: String,
        log: suspend (String) -> Unit,
    ) {
        val res = request(
            url = "https://userapi.qiekj.com/shielding/query",
            token = token,
            userAgent = ua,
            fields = mapOf("shieldingResourceType" to "1"),
        )
        log("屏蔽：${res.messageText()}")
    }

    /**
     * Reads the current task catalogue and dispatches by the server-provided
     * task type. The supplied official client defines task types 1 and 2 as
     * reward-video tasks; all other task types use task/completed.
     */
    private suspend fun runOfficialTaskList(
        token: String,
        ua: String,
        log: suspend (String) -> Unit,
        initialBalance: Int?,
    ): Int? {
        log("读取官方任务列表...")
        onProgress?.invoke("task_list", 0, 0)
        val listResponse = request(
            url = "https://userapi.qiekj.com/task/list",
            token = token,
            userAgent = ua,
            fields = emptyMap(),
        )
        if (!listResponse.isOk()) {
            log("获取任务列表失败：${listResponse.messageText()}")
            return initialBalance
        }

        val items = listResponse.items()
        if (items.isEmpty()) {
            log("官方任务列表为空")
            setState("tasklist_done", true)
            return initialBalance
        }

        var currentBalance = initialBalance
        var completedTasks = 0
        var visibleTasks = 0
        var encodedUserId: String? = null
        var encodedUserIdLookupAttempted = false

        for (item in items) {
            checkCancelled()
            waitIfPaused(log)
            val taskCode = item.string("taskCode")?.takeIf { it.isNotBlank() } ?: continue
            val title = item.string("title")?.ifBlank { null } ?: "未命名任务"
            val taskType = item.int("taskType") ?: 0
            val completedStatus = item.int("completedStatus") ?: 0
            val completedFreq = item.int("completedFreq") ?: 0
            val dailyLimit = (item.int("dailyTaskLimit") ?: item.int("totalTaskLimit") ?: 1)
                .coerceAtLeast(1)
            val remaining = (dailyLimit - completedFreq).coerceAtLeast(1)
            val stage = stageFor(taskType, title)
            visibleTasks++

            if (completedStatus == 1 || completedFreq >= dailyLimit) {
                log("$title：已完成（$completedFreq/$dailyLimit）")
                onProgress?.invoke(stage, dailyLimit, dailyLimit)
                continue
            }

            log("开始执行：$title（$completedFreq/$dailyLimit）")
            val subtasks = item.listOfMaps("subtaskList")
            var taskCompleted = false

            if (taskType == REWARD_VIDEO_TASK || taskType == REWARD_VIDEO_TASK_ALT) {
                val adId = item.rewardVideoAdId()
                if (adId.isNullOrBlank()) {
                    log("$title：缺少官方广告位，跳过领奖")
                    maybeDelay(log)
                    continue
                }

                if (!encodedUserIdLookupAttempted) {
                    encodedUserIdLookupAttempted = true
                    encodedUserId = runCatching {
                        request(
                            url = "https://userapi.qiekj.com/user/getEncodeUserId",
                            token = token,
                            userAgent = ua,
                            fields = emptyMap(),
                        )
                    }.onFailure {
                        log("获取广告用户编码失败：${it.message ?: "未知错误"}")
                    }.getOrNull()
                        ?.takeIf { it.isOk() }
                        ?.dataString()
                        ?.takeIf { it.isNotBlank() }
                    if (encodedUserId.isNullOrBlank()) {
                        log("$title：未获取到广告用户编码，跳过领奖")
                        maybeDelay(log)
                        continue
                    }
                }

                for (index in 0 until remaining) {
                    checkCancelled()
                    waitIfPaused(log)
                    log("$title 第${index + 1}/$remaining 次：展示激励广告...")
                    val adRewarded = RewardVideoGateway.showCurrent(
                        adId = adId,
                        encodedUserId = encodedUserId.orEmpty(),
                        taskCode = taskCode,
                    )
                    if (!adRewarded) {
                        log("$title 第${index + 1}/$remaining 次未完成：广告未返回有效奖励，未调用领奖接口")
                        break
                    }

                    val reward = request(
                        url = "https://userapi.qiekj.com/task/getTaskReward",
                        token = token,
                        userAgent = ua,
                        fields = mapOf("taskCode" to taskCode),
                    )
                    val rewards = reward.rewardItems()
                    if (reward.isOk()) {
                        val amount = rewards.firstOrNull()?.let { firstReward ->
                            firstReward.int("awardNumber")
                                ?: firstReward.int("awardAmount")
                        } ?: reward.rewardAmount()
                        if (amount != null && amount > 0) {
                            log("$title 第${index + 1}/$remaining 次完成，获得 $amount 分")
                            taskCompleted = true
                        } else {
                            log("$title 第${index + 1}/$remaining 次领奖接口成功，但奖励列表为空")
                        }
                    } else if (isCompletedResponse(reward)) {
                        log("$title：服务器已记录完成")
                        taskCompleted = true
                        break
                    } else {
                        log("$title 第${index + 1}/$remaining 次失败：${reward.messageText()}")
                        break
                    }
                    onProgress?.invoke(stage, index + 1, remaining)
                    maybeDelay(log)
                }
            } else if (subtasks.isNotEmpty()) {
                var subIndex = 0
                for (subtask in subtasks) {
                    if ((subtask.int("completedStatus") ?: 0) == 1) continue
                    checkCancelled()
                    waitIfPaused(log)
                    val subtaskCode = subtask.string("subtaskCode")
                        ?: subtask.string("taskCode")
                        ?: continue
                    val result = completeTask(token, ua, taskCode, subtaskCode)
                    subIndex++
                    if (result.isOk() || isCompletedResponse(result)) {
                        taskCompleted = true
                        log("$title 子任务 $subIndex 完成")
                    } else {
                        log("$title 子任务 $subIndex 失败：${result.messageText()}")
                    }
                    onProgress?.invoke(stage, subIndex, subtasks.size)
                    maybeDelay(log)
                }
            } else {
                repeat(remaining) { index ->
                    checkCancelled()
                    waitIfPaused(log)
                    val result = completeTask(token, ua, taskCode, null)
                    if (result.isOk() || isCompletedResponse(result)) {
                        taskCompleted = true
                        log("$title 第${index + 1}/$remaining 次完成")
                    } else {
                        log("$title 第${index + 1}/$remaining 次失败：${result.messageText()}")
                        return@repeat
                    }
                    onProgress?.invoke(stage, index + 1, remaining)
                    maybeDelay(log)
                }
            }

            if (taskCompleted) {
                completedTasks++
                markLegacyState(title, taskType)
                currentBalance = logBalanceDelta(title, token, ua, currentBalance, log)
            }
            maybeDelay(log)
        }

        setState("tasklist_done", true)
        log("任务列表处理完成：$completedTasks/$visibleTasks 项")
        return currentBalance
    }

    private suspend fun completeTask(
        token: String,
        ua: String,
        taskCode: String,
        subtaskCode: String?,
    ): Map<String, Any?> {
        val fields = buildMap {
            put("taskCode", taskCode)
            if (!subtaskCode.isNullOrBlank()) put("subtaskCode", subtaskCode)
        }
        return request(
            url = "https://userapi.qiekj.com/task/completed",
            token = token,
            userAgent = ua,
            fields = fields,
        )
    }

    private suspend fun logBalanceDelta(
        label: String,
        token: String,
        ua: String,
        previous: Int?,
        log: suspend (String) -> Unit,
    ): Int? {
        val current = balance(token, ua)
        if (current != null && previous != null) {
            log("$label：+${current - previous}（$current）")
        } else if (current != null) {
            log("$label：$current")
        }
        return current ?: previous
    }

    private suspend fun balance(token: String, ua: String): Int? {
        val res = request(
            url = "https://userapi.qiekj.com/user/balance",
            token = token,
            userAgent = ua,
            fields = emptyMap(),
        )
        return res.dataMap()["integral"].asInt()
    }

    private fun Map<String, Any?>.rewardVideoAdId(): String? {
        val extendMap = this["extendMap"].asStringMap()
        val v180 = extendMap?.get("v180").asStringMap()
        val android = v180?.get("android").asStringMap()
        return android?.string("gromoreId")
            ?.takeIf { it.isNotBlank() }
            ?: android?.string("newGromoreId")?.takeIf { it.isNotBlank() }
            ?: this.string("gromoreId")?.takeIf { it.isNotBlank() }
    }

    private suspend fun request(
        url: String,
        token: String,
        userAgent: String,
        fields: Map<String, String>,
    ): Map<String, Any?> {
        val timestamp = System.currentTimeMillis().toString()
        val actualFields = if (fields.containsKey("token")) fields else fields + ("token" to token)
        val form = FormBody.Builder().apply {
            actualFields.forEach { (key, value) -> add(key, value) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .headers(headers(url, token, userAgent, timestamp))
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    debugLog?.e("Runner", "HTTP ${response.code}: ${url.substringAfterLast('/')}, body=${body.take(300)}")
                    error("HTTP ${response.code} (${url.substringAfterLast('/')}): ${body.take(300)}")
                }
                val result = runCatching { jsonAdapter.fromJson(body).orEmpty() }
                    .getOrElse { error("响应解析失败：${it.message ?: body.take(300)}") }
                debugLog?.d(
                    "Runner",
                    "${url.substringAfterLast('/')} code=${result.codeInt()} msg=${result.messageText()}",
                )
                result
            }
        }
    }

    private fun headers(
        url: String,
        token: String,
        userAgent: String,
        timestamp: String,
    ): okhttp3.Headers {
        val path = url.substringAfter("https://userapi.qiekj.com")
        val raw =
            "appSecret=${ApiConfig.ANDROID_SECRET}&channel=${ApiConfig.API_CHANNEL}&timestamp=$timestamp&token=$token&version=${ApiConfig.VERSION}&$path"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sign = digest.joinToString("") { "%02x".format(it) }
        return okhttp3.Headers.Builder()
            .add("Authorization", token)
            .add("Version", ApiConfig.VERSION)
            .add("channel", ApiConfig.API_CHANNEL)
            .add("phoneBrand", ApiConfig.PHONE_BRAND)
            .add("timestamp", timestamp)
            .add("sign", sign)
            .add("Content-Type", ApiConfig.CONTENT_TYPE)
            .add("Host", "userapi.qiekj.com")
            .add("Connection", "Keep-Alive")
            .add("User-Agent", userAgent.ifBlank { ApiConfig.USER_AGENT })
            .build()
    }

    private fun markLegacyState(title: String, taskType: Int) {
        val lower = title.lowercase()
        when {
            taskType == REWARD_VIDEO_TASK || taskType == REWARD_VIDEO_TASK_ALT -> setState("app_video_done", true)
            "首页" in title -> setState("home_page_done", true)
            "广告" in title || "视频" in title -> setState("ad_task_done", true)
            else -> setState("other_task_done", true)
        }
        if ("支付宝" in title || "alipay" in lower) {
            setState("alipay_video_task_done", true)
        }
    }

    private fun stageFor(taskType: Int, title: String): String = when {
        taskType == REWARD_VIDEO_TASK || taskType == REWARD_VIDEO_TASK_ALT -> "app_video"
        "支付宝" in title || title.lowercase().contains("alipay") -> "alipay_video_task"
        "首页" in title -> "home_page"
        "广告" in title -> "ad_task"
        else -> "task_list"
    }

    private fun isCompletedResponse(response: Map<String, Any?>): Boolean {
        val msg = response.messageText()
        return msg.contains("已完成") || msg.contains("已结束") || msg.contains("已达上限") ||
            msg.contains("次数已满") || (response.isOk() && response["data"] == false)
    }

    private fun Map<String, Any?>.isOk(): Boolean = codeInt() == 0 || codeInt() == 200

    private fun Map<String, Any?>.codeInt(): Int? = this["code"].asInt()

    private fun Map<String, Any?>.messageText(): String =
        this["msg"]?.toString() ?: this["message"]?.toString() ?: "未知结果"

    private fun Map<String, Any?>.dataMap(): Map<String, Any?> =
        (this["data"] as? Map<*, *>)?.toStringMap().orEmpty()

    private fun Map<String, Any?>.items(): List<Map<String, Any?>> =
        dataMap()["items"].asMapList()
            .ifEmpty { dataMap()["records"].asMapList() }
            .ifEmpty { dataMap()["list"].asMapList() }
            .ifEmpty { this["data"].asMapList() }

    private fun Map<String, Any?>.rewardItems(): List<Map<String, Any?>> =
        this["data"].asMapList()
            .ifEmpty { dataMap()["items"].asMapList() }

    private fun Map<String, Any?>.rewardAmount(): Int? =
        dataMap()["awardNumber"].asInt()
            ?: dataMap()["awardAmount"].asInt()
            ?: this["awardNumber"].asInt()
            ?: this["awardAmount"].asInt()

    private fun Map<String, Any?>.dataString(): String? = when (val value = this["data"]) {
        is String -> value
        is Number -> value.toString()
        else -> null
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()

    private fun Map<String, Any?>.int(key: String): Int? = this[key].asInt()

    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
        this[key].asMapList()

    private fun Any?.asInt(): Int? = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull()
        else -> null
    }

    private fun Any?.asMapList(): List<Map<String, Any?>> =
        (this as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.toStringMap() }.orEmpty()

    private fun Any?.asStringMap(): Map<String, Any?>? =
        (this as? Map<*, *>)?.toStringMap()

    private fun Map<*, *>.toStringMap(): Map<String, Any?> =
        entries.associate { it.key.toString() to it.value }

    private companion object {
        const val DEFAULT_SIGN_IN_ACTIVITY_ID = "600001"
        const val REWARD_VIDEO_TASK = 1
        const val REWARD_VIDEO_TASK_ALT = 2
    }
}
