package com.inonvation.lightlife.data

/**
 * Logs the business status without treating a human-readable message as success
 * or exposing response payloads (tokens, account IDs, etc.).
 */
internal object TaskResponseDiagnostics {
    const val REVISION = "task-diag-v1"

    fun code(response: Map<String, Any?>): Int? = when (val value = response["code"]) {
        is String -> value.trim().toIntOrNull()
        is Number -> value.toDouble().takeIf {
            it.isFinite() && it % 1.0 == 0.0 &&
                it >= Int.MIN_VALUE.toDouble() && it <= Int.MAX_VALUE.toDouble()
        }?.toInt()
        else -> null
    }

    fun isOk(response: Map<String, Any?>): Boolean =
        code(response) == 0 || code(response) == 200

    fun message(response: Map<String, Any?>): String =
        (response["msg"] as? String)?.takeIf { it.isNotBlank() }
            ?: (response["message"] as? String)?.takeIf { it.isNotBlank() }
            ?: "未知结果"

    fun requiresClientUpgrade(response: Map<String, Any?>): Boolean {
        val message = message(response)
        return message.contains("版本过低") ||
            (message.contains("版本") && message.contains("升级"))
    }

    fun isAccepted(response: Map<String, Any?>): Boolean =
        isOk(response) && response["data"] != false && !requiresClientUpgrade(response)

    fun isCompletionRecorded(response: Map<String, Any?>): Boolean {
        if (requiresClientUpgrade(response)) return false
        val message = message(response)
        // data=false alone is not evidence of an already-completed task.
        return message.contains("已完成") || message.contains("已结束") ||
            message.contains("已达上限") || message.contains("次数已满")
    }

    fun summary(response: Map<String, Any?>): String {
        val codeLabel = code(response)?.toString()
            ?: if (response.containsKey("code")) "无效" else "缺失"
        val dataLabel = when (val data = response["data"]) {
            null -> if (response.containsKey("data")) "null" else "缺失"
            is Boolean -> "boolean($data)"
            is List<*> -> "list(${data.size})"
            is Map<*, *> -> "object"
            is String -> "string"
            is Number -> "number"
            else -> "未知类型"
        }
        val message = message(response).replace(Regex("[\\p{Cc}]"), " ").take(120)
        return "code=$codeLabel，data=$dataLabel，msg=$message"
    }
}
