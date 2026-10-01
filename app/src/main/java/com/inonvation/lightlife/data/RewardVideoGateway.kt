package com.inonvation.lightlife.data

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.bytedance.sdk.openadsdk.AdSlot
import com.bytedance.sdk.openadsdk.TTAdNative
import com.bytedance.sdk.openadsdk.TTAdSdk
import com.bytedance.sdk.openadsdk.TTRewardVideoAd
import com.bytedance.sdk.openadsdk.mediation.ad.MediationAdSlot
import com.inonvation.lightlife.ActivityRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Runs the same reward-video sequence as the official client:
 * load -> show -> wait for onRewardArrived(true) -> close -> claim task reward.
 */
object RewardVideoGateway {
    private const val CSJ_APP_ID = "5356474"
    private const val APP_NAME = "胖乖生活"
    private val mutex = Mutex()
    @Volatile
    private var initialized = false

    fun initialize(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching {
                initializeSdk(application)
                TTAdSdk.start(object : TTAdSdk.Callback {
                    override fun success() = Unit
                    override fun fail(code: Int, message: String?) {
                        Log.e(TAG, "TTAdSdk start failed: $code $message")
                    }
                })
                initialized = true
            }.onFailure {
                Log.e(TAG, "TTAdSdk initialization failed", it)
            }
        }
    }

    /**
     * TTAdConfig is intentionally hidden from the SDK's public compile stubs
     * in this SDK build, although TTAdSdk.init accepts it at runtime.
     */
    private fun initializeSdk(application: Application) {
        val builderClass = Class.forName("com.bytedance.sdk.openadsdk.TTAdConfig\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        fun call(name: String, vararg args: Any?) {
            val method = builderClass.methods.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.size == args.size &&
                    candidate.parameterTypes.zip(args).all { (parameterType, argument) ->
                        argument != null && parameterType.accepts(argument.javaClass)
                    }
            } ?: error("TTAdConfig.Builder.$name method not found")
            method.invoke(builder, *args)
        }
        call("appId", CSJ_APP_ID)
        call("appName", APP_NAME)
        call("useMediation", true)
        call("titleBarTheme", 1)
        call("allowShowNotify", true)
        call("debug", false)
        call("supportMultiProcess", true)
        val config = builderClass.getMethod("build").invoke(builder)
        val initMethod = TTAdSdk::class.java.methods.firstOrNull { method ->
            method.name == "init" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].isAssignableFrom(application.javaClass) &&
                method.parameterTypes[1].isAssignableFrom(config.javaClass)
        } ?: error("TTAdSdk.init method not found")
        initMethod.invoke(null, application, config)
    }

    private fun Class<*>.accepts(argumentClass: Class<*>): Boolean {
        if (isAssignableFrom(argumentClass)) return true
        if (!isPrimitive) return false
        return when (this) {
            Boolean::class.javaPrimitiveType -> argumentClass == Boolean::class.java
            Int::class.javaPrimitiveType -> argumentClass == Int::class.java
            Long::class.javaPrimitiveType -> argumentClass == Long::class.java
            Float::class.javaPrimitiveType -> argumentClass == Float::class.java
            Double::class.javaPrimitiveType -> argumentClass == Double::class.java
            Short::class.javaPrimitiveType -> argumentClass == Short::class.java
            Byte::class.javaPrimitiveType -> argumentClass == Byte::class.java
            Char::class.javaPrimitiveType -> argumentClass == Char::class.java
            else -> false
        }
    }

    suspend fun show(
        activity: Activity,
        adId: String,
        encodedUserId: String,
        taskCode: String,
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val ready = withTimeoutOrNull(10_000L) {
                while (!TTAdSdk.isSdkReady()) delay(100)
                true
            } == true
            if (!ready) false else withTimeoutOrNull(45_000L) {
                showOnMain(activity, adId, encodedUserId, taskCode)
            } == true
        }
    }

    suspend fun showCurrent(
        adId: String,
        encodedUserId: String,
        taskCode: String,
    ): Boolean {
        val activity = ActivityRegistry.current() ?: return false
        return show(activity, adId, encodedUserId, taskCode)
    }

    private suspend fun showOnMain(
        activity: Activity,
        adId: String,
        encodedUserId: String,
        taskCode: String,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        if (activity.isFinishing || activity.isDestroyed || adId.isBlank() || taskCode.isBlank()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        if (!TTAdSdk.isSdkReady()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val completed = AtomicBoolean(false)
        val shown = AtomicBoolean(false)
        val rewarded = AtomicBoolean(false)
        var ad: TTRewardVideoAd? = null

        fun destroy() {
            ad?.mediationManager?.destroy()
            ad = null
        }

        fun finish(result: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            destroy()
            if (continuation.isActive) continuation.resume(result)
        }

        fun showLoadedAd(loaded: TTRewardVideoAd) {
            if (!shown.compareAndSet(false, true) || completed.get()) return
            ad = loaded
            loaded.setRewardAdInteractionListener(object : TTRewardVideoAd.RewardAdInteractionListener {
                override fun onAdShow() = Unit
                override fun onAdVideoBarClick() = Unit
                override fun onVideoComplete() = Unit
                override fun onSkippedVideo() = finish(false)
                override fun onVideoError() = finish(false)
                override fun onRewardVerify(
                    rewardVerify: Boolean,
                    rewardAmount: Int,
                    rewardName: String?,
                    errorCode: Int,
                    errorMsg: String?,
                ) = Unit

                override fun onRewardArrived(
                    isRewardValid: Boolean,
                    rewardType: Int,
                    extraInfo: Bundle?,
                ) {
                    if (isRewardValid) rewarded.set(true)
                }

                override fun onAdClose() {
                    finish(rewarded.get())
                }
            })
            loaded.showRewardVideoAd(activity)
        }

        val extra = JSONObject().put("taskCode", taskCode).toString()
        val slot = AdSlot.Builder()
            .setCodeId(adId)
            .setUserID(encodedUserId)
            .setMediaExtra(extra)
            .setExt(extra)
            .setMediationAdSlot(
                MediationAdSlot.Builder()
                    .setExtraObject("gromoreExtra", extra)
                    .build(),
            )
            .setOrientation(1)
            .build()

        runCatching {
            TTAdSdk.getAdManager()
                .createAdNative(activity)
                .loadRewardVideoAd(slot, object : TTAdNative.RewardVideoAdListener {
                    override fun onError(code: Int, message: String?) {
                        finish(false)
                    }

                    override fun onRewardVideoAdLoad(loaded: TTRewardVideoAd) {
                        showLoadedAd(loaded)
                    }

                    override fun onRewardVideoCached() = Unit

                    override fun onRewardVideoCached(loaded: TTRewardVideoAd) {
                        showLoadedAd(loaded)
                    }
                })
        }.onFailure {
            finish(false)
        }

        continuation.invokeOnCancellation {
            destroy()
        }
    }

    private const val TAG = "RewardVideoGateway"
}
