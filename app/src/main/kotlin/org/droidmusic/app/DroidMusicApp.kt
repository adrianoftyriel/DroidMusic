package org.droidmusic.app

import android.app.Application
import android.os.Build
import android.provider.Settings
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.droidmusic.app.data.LibraryRepository
import org.droidmusic.app.data.SettingsRepository
import org.droidmusic.app.data.SetlistRepository
import org.droidmusic.app.net.SessionDiscovery

/**
 * The application, and the container everything is built from.
 *
 * Hand-wired rather than a dependency-injection framework. There are five
 * objects here with an obvious construction order and no cycles; annotation
 * processing to build that graph would add build time and a layer of indirection
 * in exchange for nothing.
 */
class DroidMusicApp : Application() {

    val appScope = CoroutineScope(SupervisorJob())

    lateinit var settings: SettingsRepository
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var setlists: SetlistRepository
        private set
    lateinit var discovery: SessionDiscovery
        private set

    override fun onCreate() {
        super.onCreate()

        settings = SettingsRepository(filesDir, appScope)
        library = LibraryRepository(filesDir, appScope)
        setlists = SetlistRepository(filesDir, appScope)
        discovery = SessionDiscovery(this)

        appScope.launch {
            settings.load()
            library.load()
            setlists.load()

            // First run: give the device a name and an identity, both of which
            // other devices in a session will see.
            settings.update { current ->
                current.copy(
                    deviceId = current.deviceId.ifEmpty { newDeviceId() },
                    deviceName = current.deviceName.ifEmpty { defaultDeviceName() },
                )
            }
        }
    }

    /**
     * A per-install identifier, generated rather than read from the hardware.
     *
     * ANDROID_ID would be stable across reinstalls, which is exactly why it is
     * not used: a page-turner has no business carrying a device fingerprint that
     * outlives it. This id exists so a phone that drops off the wifi is
     * recognised as the same phone when it comes back, and a random UUID does
     * that perfectly.
     */
    private fun newDeviceId(): String = UUID.randomUUID().toString()

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        val name = runCatching {
            Settings.Global.getString(contentResolver, "device_name")
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: model
    }

    companion object {
        val VERSION: String = BuildConfig.VERSION_NAME
    }
}
