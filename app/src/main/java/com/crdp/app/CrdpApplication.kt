package com.crdp.app

import android.app.Application
import com.crdp.app.printer.PrinterSpoolWatcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CrdpApplication : Application() {

    /**
     * Started unconditionally — the watcher does no work until the FreeRDP
     * backend actually writes to the spool dir, so the cost of having it
     * armed is a single FileObserver thread on the inotify FD.
     */
    @Inject lateinit var printerSpoolWatcher: PrinterSpoolWatcher

    override fun onCreate() {
        super.onCreate()
        runCatching { printerSpoolWatcher.start() }
    }
}
