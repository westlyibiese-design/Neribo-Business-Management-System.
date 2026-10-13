package com.westly.nbms.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One place that knows whether this phone can really reach the internet.
 * "Online" means the network has internet AND the system has checked that it works (a Wi-Fi router with no
 * internet behind it counts as offline). [start] is called once from the Application class.
 * Until [start] runs (and in unit tests) the state is "online", which keeps the old behaviour.
 */
object NetworkState {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    val isOnline: Boolean get() = _online.value

    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        _online.value = currentlyOnline(cm)
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = currentlyOnline(cm)
                }

                override fun onLost(network: Network) {
                    _online.value = currentlyOnline(cm)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _online.value = isUsable(networkCapabilities)
                }
            })
        } catch (e: Exception) {
            // If the system refuses the callback we keep the value read above; writes still work offline.
        }
    }

    private fun currentlyOnline(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return isUsable(caps)
    }

    private fun isUsable(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Reads come from the phone's saved copy when offline, so a screen never waits for a server that is not there. */
fun readSource(): Source = if (NetworkState.isOnline) Source.DEFAULT else Source.CACHE
