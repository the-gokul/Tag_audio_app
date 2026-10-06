package com.nordic.tagmobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** UI hooks for BLE connection changes (Home, etc.). Main-thread callbacks. */
object BleUiBridge {
    fun interface Observer {
        fun onBleConnectionChanged(connected: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()

    fun addObserver(observer: Observer) {
        if (!observers.contains(observer)) observers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun notifyConnectionChanged(connected: Boolean) {
        val run = Runnable {
            observers.forEach { it.onBleConnectionChanged(connected) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }
}
