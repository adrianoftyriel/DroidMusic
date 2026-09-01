package org.droidmusic.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * nothing however well everything else works.
 */
class SessionDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(): Flow<List<DiscoveredSession>> = callbackFlow {
        val found = ConcurrentHashMap<String, DiscoveredSession>()
        val resolving = ConcurrentHashMap.newKeySet<String>()

        fun publish() {
            trySend(found.values.sortedBy { it.serviceName })
        }

        /**
         * Resolves one service, retrying a few times.
         *
         * A fresh listener object every time, which is not a style choice: the
         * platform allows one resolve per listener and throws
         * `IllegalArgumentException("listener already in use")` for a second.
         * Sharing one listener across every service found - as this did - meant
         * that on a network with two sessions, or after any service was seen
         * twice, resolution silently stopped and the leader never appeared in
         * the list.
         *
         * The retry covers the other half: a resolve can come back
         * `FAILURE_ALREADY_ACTIVE` while the platform is busy with another, and
         * a resolve that failed is never tried again on its own.
         */
        fun resolve(serviceInfo: NsdServiceInfo, attempt: Int = 0) {
            val name = serviceInfo.serviceName ?: return
            if (!resolving.add(name)) return

            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving.remove(name)
                    if (attempt < RESOLVE_ATTEMPTS) {
                        launch {
                            delay(RESOLVE_RETRY_MS * (attempt + 1))
                            resolve(info, attempt + 1)
                        }
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving.remove(name)
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress ?: return
                    found[name] = DiscoveredSession(
                        serviceName = name,
                        host = host,
                        port = info.port,
                        leaderName = info.attributes?.get(ATTR_LEADER)
                            ?.let { String(it, Charsets.UTF_8) },
                    )
                    publish()
                }
            }

            runCatching {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, listener)
            }.onFailure { resolving.remove(name) }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolving is a separate round trip; a found service has a name
                // but no address until it completes.
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                serviceInfo.serviceName?.let { found.remove(it) }
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

    /**
     * Looks up one session by name, now.
     *
     * This is what a reconnecting follower needs. The address it joined on is
     * not durable: the leader binds an ephemeral port, so a leader whose app
     * restarted is listening somewhere else entirely, and a phone that changed
     * wifi has a different address as well. A follower retrying the address it
     * first saw will retry it for the rest of the night and never connect again,
     * which reads on stage as "it just stopped following".
     *
     * Returns null if the session cannot be found within [timeoutMs], which is
     * not an error - the leader may simply have gone home, and the caller falls
     * back to the address it already has.
     */
    suspend fun resolveOnce(serviceName: String, timeoutMs: Long = RESOLVE_TIMEOUT_MS): DiscoveredSession? =
        withTimeoutOrNull(timeoutMs) {
            discover()
                .mapNotNull { sessions -> sessions.firstOrNull { it.serviceName == serviceName } }
                .first()
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

        /** Retries of a failed resolve, and the step between them. */
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_RETRY_MS = 400L

        /**
         * How long to look for a named session before giving up.
         *
         * Generous, because this runs on a reconnect rather than in front of
         * somebody waiting: mDNS on a busy access point can take seconds, and
         * the alternative to waiting is retrying an address known to be dead.
         */
        const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}
