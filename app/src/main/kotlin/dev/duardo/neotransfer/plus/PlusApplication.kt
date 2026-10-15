package dev.duardo.neotransfer.plus

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.duardo.neotransfer.plus.app.PlusApp

/**
 * Starts the app state with the process: opening the app, or WorkManager running the credit check with the app
 * closed (the credit notices are posted from here in both cases).
 */
class PlusApplication : Application() {
    lateinit var plus: PlusApp
        private set

    override fun onCreate() {
        super.onCreate()
        plus = PlusApp(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = plus.onForeground()
            override fun onStop(owner: LifecycleOwner) = plus.onBackground()
        })
    }
}

val Context.plus: PlusApp get() = (applicationContext as PlusApplication).plus
