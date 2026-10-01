package com.inonvation.lightlife

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.inonvation.lightlife.data.RewardVideoGateway

class LightLifeApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ActivityRegistry.set(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                ActivityRegistry.clear(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                ActivityRegistry.clear(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        RewardVideoGateway.initialize(this)
    }
}
