package com.westly.nbms

import android.app.Application
import com.westly.nbms.core.data.NetworkState
import com.westly.nbms.core.notify.PushOutbox
import com.westly.nbms.core.sync.OfflineSyncCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NbmsApplication : Application() {

    @Inject
    lateinit var offlineSync: OfflineSyncCoordinator

    override fun onCreate() {
        // The phone's connection state must be known before anything else is built (the sign-in uses it).
        NetworkState.start(this)
        PushOutbox.init(this)
        super.onCreate()
        offlineSync.start()
    }
}
