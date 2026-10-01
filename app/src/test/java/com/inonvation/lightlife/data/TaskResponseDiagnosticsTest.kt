package com.inonvation.lightlife.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskResponseDiagnosticsTest {
    @Test
    fun successMessageDoesNotOverrideBusinessError() {
        val response = mapOf("code" to 9999, "msg" to "成功", "data" to null)
        assertFalse(TaskResponseDiagnostics.isAccepted(response))
        assertEquals("code=9999，data=null，msg=成功", TaskResponseDiagnostics.summary(response))
        println("REJECTED_SUCCESS_MESSAGE: ${TaskResponseDiagnostics.summary(response)}")
    }

    @Test
    fun successMessageWithoutCodeIsNotAccepted() {
        val response = mapOf("msg" to "成功")
        assertFalse(TaskResponseDiagnostics.isAccepted(response))
        assertEquals("code=缺失，data=缺失，msg=成功", TaskResponseDiagnostics.summary(response))
    }

    @Test
    fun supportedCodesRemainAccepted() {
        for (code in listOf(0, 200, 200.0, "200", " 200 ")) {
            val response = mapOf("code" to code, "data" to true)
            assertTrue(TaskResponseDiagnostics.isAccepted(response))
        }
    }

    @Test
    fun falseDataIsNeitherAcceptedNorCompleted() {
        val response = mapOf("code" to 200, "msg" to "成功", "data" to false)
        assertFalse(TaskResponseDiagnostics.isAccepted(response))
        assertFalse(TaskResponseDiagnostics.isCompletionRecorded(response))
        assertEquals("code=200，data=boolean(false)，msg=成功", TaskResponseDiagnostics.summary(response))
        println("FALSE_DATA_NOT_COMPLETED: ${TaskResponseDiagnostics.summary(response)}")
    }

    @Test
    fun explicitCompletedMessageIsRecognized() {
        val response = mapOf("code" to 9999, "msg" to "任务已完成", "data" to false)
        assertTrue(TaskResponseDiagnostics.isCompletionRecorded(response))
    }

    @Test
    fun upgradeResponseStopsEvenIfEnvelopeClaimsSuccess() {
        val response = mapOf("code" to 200, "msg" to "版本过低，请升级APP后再使用")
        assertTrue(TaskResponseDiagnostics.requiresClientUpgrade(response))
        assertFalse(TaskResponseDiagnostics.isAccepted(response))
        assertFalse(TaskResponseDiagnostics.isCompletionRecorded(response))
        println("UPGRADE_REJECTED: ${TaskResponseDiagnostics.summary(response)}")
    }

    @Test
    fun completedWordDoesNotOverrideUpgradeRequired() {
        val response = mapOf("code" to 9999, "msg" to "版本过低，升级后才能查看已完成任务")
        assertFalse(TaskResponseDiagnostics.isCompletionRecorded(response))
    }

    @Test
    fun fractionalAndOutOfRangeCodesAreNotTruncatedToSuccess() {
        for (code in listOf(200.5, 0.25, Double.NaN, Double.POSITIVE_INFINITY, 4294967496L, true, "200.5")) {
            assertNull(TaskResponseDiagnostics.code(mapOf("code" to code)))
            assertFalse(TaskResponseDiagnostics.isOk(mapOf("code" to code)))
        }
    }

    @Test
    fun blankMsgFallsBackToMessage() {
        assertEquals(
            "版本过低",
            TaskResponseDiagnostics.message(mapOf("msg" to " ", "message" to "版本过低")),
        )
    }

    @Test
    fun summaryDoesNotPrintSensitivePayload() {
        val response = mapOf(
            "code" to 200,
            "msg" to "成功",
            "data" to mapOf("token" to "SENSITIVE_TOKEN", "userId" to "PRIVATE_USER"),
        )
        assertEquals("code=200，data=object，msg=成功", TaskResponseDiagnostics.summary(response))
        assertFalse(TaskResponseDiagnostics.summary(response).contains("SENSITIVE_TOKEN"))
        assertFalse(TaskResponseDiagnostics.summary(response).contains("PRIVATE_USER"))
    }

    @Test
    fun summaryUsesShapeForListsAndStrings() {
        assertTrue(TaskResponseDiagnostics.summary(mapOf("data" to listOf("PRIVATE"))).contains("data=list(1)"))
        assertTrue(TaskResponseDiagnostics.summary(mapOf("data" to "PRIVATE")).contains("data=string"))
        assertFalse(TaskResponseDiagnostics.summary(mapOf("data" to "PRIVATE")).contains("PRIVATE"))
    }

    @Test
    fun summaryIsOneBoundedLogLine() {
        val response = mapOf("code" to 500, "msg" to ("错误\n\t" + "x".repeat(1000)))
        val summary = TaskResponseDiagnostics.summary(response)
        assertFalse(summary.contains('\n'))
        assertFalse(summary.contains('\t'))
        assertTrue(summary.length < 170)
    }
}
