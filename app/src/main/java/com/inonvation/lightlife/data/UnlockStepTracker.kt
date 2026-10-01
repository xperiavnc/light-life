package com.inonvation.lightlife.data

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** Keeps the real failing step even when conversion fails before a DTO exists. */
internal class UnlockStepTracker(private val onStep: suspend (String) -> Unit) {
    private var currentStep = "准备解锁"

    suspend fun report(step: String) {
        currentStep = step
        onStep(step)
    }

    suspend fun <T> run(block: suspend UnlockStepTracker.() -> T): T = try {
        block()
    } catch (e: Exception) {
        rethrow(e, currentStep)
    }

    companion object {
        fun rethrow(original: Throwable, step: String): Nothing {
            when (original) {
                is CancellationException, is TokenExpiredException, is UnlockException, is Error -> throw original
            }
            val code = when (original) {
                is ApiBusinessException -> original.code
                is HttpException -> original.code()
                else -> Regex("^HTTP (\\d+)").find(original.message.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
            }
            val diagnosis = DeviceErrorDiagnosis.diagnose(code, original.message, step)
            throw UnlockException(diagnosis.primaryReason, diagnosis, original)
        }
    }
}
