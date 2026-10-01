package com.inonvation.lightlife

import android.app.Activity
import java.lang.ref.WeakReference

object ActivityRegistry {
    @Volatile
    private var current: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        current = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    fun current(): Activity? =
        current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}
