package org.droidmusic.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.droidmusic.session.SERVICE_TYPE

data class DiscoveredSession(
    val serviceName: String,
    val host: String,
    val port: Int,
    val leaderName: String? = null,
)

/**
 * Finding the band leader on the local network, over mDNS.
 *
 * Chosen over a broadcast or a hard-coded address because it is the only option
 * that needs no configuration at all: the leader taps "start", the others tap
 * the name that appears. On a venue's wifi nobody knows the subnet and nobody
 * wants to type an IP address in the ninety seconds before the first song.
 *
 * The failure mode is worth knowing about, and the UI says so: some access
 * points block multicast between clients, and on those networks discovery finds
 * nothing however well everything else works. That is why [SessionConnector]
 * also accepts an address typed in by hand.
 */
class SessionDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(): Flow<List<DiscoveredSession>> = callbackFlow {
        val found = linkedMapOf<String, DiscoveredSession>()

        fun publish() {
            trySend(found.values.toList())
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                found[serviceInfo.serviceName] = DiscoveredSession(
                    serviceName = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port,
                    leaderName = serviceInfo.attributes?.get(ATTR_LEADER)
                        ?.let { String(it, Charsets.UTF_8) },
                )
                publish()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolving is a separate round trip; a found service has a name
                // but no address until it completes.
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, resolveListener)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS discovery failed with code $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { close(it) }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }

    /** Advertises this device as the leader. Returns a handle to stop with. */
    fun advertise(sessionName: String, leaderName: String, port: Int): Registration {
        val info = NsdServiceInfo().apply {
            this.serviceName = sessionName
            this.serviceType = SERVICE_TYPE
            this.port = port
            setAttribute(ATTR_LEADER, leaderName)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        return Registration { runCatching { nsdManager.unregisterService(listener) } }
    }

    fun interface Registration {
        fun stop()
    }

    companion object {
        const val ATTR_LEADER = "leader"
    }
}
