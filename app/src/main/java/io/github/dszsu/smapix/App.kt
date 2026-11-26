package io.github.dszsu.smapix

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(xposedService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService == service) {
            xposedService = null
            listeners.forEach { it(null) }
        }
    }
}
