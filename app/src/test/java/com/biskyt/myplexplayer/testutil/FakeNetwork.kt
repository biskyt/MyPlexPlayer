package com.biskyt.myplexplayer.testutil

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * What Android tells the app as the default network changes: a network with or without a VPN, or ([vpn] null) none.
 * [validated]: Android found that it reaches the internet.
 */
fun ConnectivityManager.reportNetwork(vpn: Boolean?, validated: Boolean = false) {
    val network = ShadowNetwork.newInstance(1)
    shadowOf(this).networkCallbacks.forEach { callback ->
        if (vpn == null) {
            callback.onLost(network)
        } else {
            val caps = ShadowNetworkCapabilities.newInstance()
            if (vpn) shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            callback.onCapabilitiesChanged(network, caps)
        }
    }
}
