package io.github.shiroel.aries

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Tells [onChange] (on the main thread) when the phone's default network
 * changes: Wi-Fi to LTE, airplane mode on or off. The network in use when it
 * starts is not a change.
 */
class NetworkWatch(context: Context, private val onChange: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var current: Network? = connectivity?.activeNetwork

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (network != current) {
                    current = network
                    onChange()
                }
            }
        }

        override fun onLost(network: Network) {
            main.post {
                if (network == current) {
                    current = null
                    onChange()
                }
            }
        }
    }

    fun start() {
        connectivity?.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        connectivity?.unregisterNetworkCallback(callback)
    }
}
