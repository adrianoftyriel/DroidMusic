package org.droidmusic.app.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.droidmusic.app.BuildConfig
import org.droidmusic.update.AvailableUpdate
import org.droidmusic.update.UpdateChannel
import org.droidmusic.update.UpdateCheck
import org.droidmusic.update.UpdateStatus

/** Where the update flow has got to. */
enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY }

/**
 * Drives checking for, fetching and installing a newer build.
 *
 * Every transition here starts with somebody pressing something. There is no
 * timer, no check on launch and no background work - see [Updater] for why that
 * is a deliberate constraint rather than an omission.
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var phase by mutableStateOf(UpdatePhase.IDLE)
        private set
    var status by mutableStateOf<UpdateStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** 0..1 while downloading, or -1 when the server did not say how big it is. */
    var progress by mutableStateOf(0f)
        private set

    var downloaded by mutableStateOf<File?>(null)
        private set

    /** Whether the downloaded file's checksum was actually checked. */
    var verified by mutableStateOf(false)
        private set

    /**
     * The release this build came from, or null for one that CI did not build.
     *
     * Empty rather than absent in BuildConfig because a string field cannot be
     * null there; the emptiness is what means "no release".
     */
    val currentTag: String? = BuildConfig.RELEASE_TAG.takeIf { it.isNotBlank() }

    val repository: String = BuildConfig.UPDATE_REPOSITORY

    /**
     * Whether this build can be updated in place at all.
     *
     * A debug build has `.debug` appended to its application id, so installing
     * the release APK would not replace it - it would put a second DroidMusic on
     * the phone, with its own empty library. Better to say so than to do that.
     */
    val canUpdateThisBuild: Boolean = !BuildConfig.DEBUG

    /** Whether the user has let this app install packages. Re-read on every look. */
    fun canInstall(): Boolean = Updater.canInstallPackages(context)

    val available: AvailableUpdate?
        get() = when (val current = status) {
            is UpdateStatus.Available -> current.update
            is UpdateStatus.UnknownCurrent -> current.newest
            else -> null
        }

    fun check(channel: UpdateChannel) {
        if (phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING) return
        scope.launch {
            phase = UpdatePhase.CHECKING
            error = null
            status = null
            downloaded = null

            when (val result = Updater.fetchReleases(repository)) {
                is Updater.FetchResult.Failed -> error = result.reason
                is Updater.FetchResult.Ok ->
                    status = UpdateCheck.check(currentTag, result.releases, channel)
            }
            phase = UpdatePhase.IDLE
        }
    }

    fun download() {
        val update = available ?: return
        if (phase == UpdatePhase.DOWNLOADING) return
        scope.launch {
            phase = UpdatePhase.DOWNLOADING
            error = null
            progress = 0f

            when (val result = Updater.download(context, update) { progress = it }) {
                is Updater.DownloadResult.Failed -> {
                    error = result.reason
                    downloaded = null
                    phase = UpdatePhase.IDLE
                }
                is Updater.DownloadResult.Ready -> {
                    downloaded = result.file
                    verified = result.verified
                    phase = UpdatePhase.READY
                }
            }
        }
    }

    /**
     * Hands the APK to the system installer, which then asks the user itself.
     *
     * Nothing is cleaned up here. The installer reads the file after this
     * returns, so deleting it now is a race - and the download lives in the
     * cache directory, which the system reclaims on its own.
     */
    fun install(): Boolean {
        val file = downloaded ?: return false
        return runCatching {
            context.startActivity(Updater.installIntent(context, file))
            true
        }.getOrElse {
            error = "Android would not open the installer for that file."
            false
        }
    }

    fun openUnknownSourcesSettings() {
        runCatching { context.startActivity(Updater.unknownSourcesIntent(context)) }
    }

    fun discard() {
        Updater.clearDownloads(context)
        downloaded = null
        verified = false
        phase = UpdatePhase.IDLE
    }

    fun dismissError() {
        error = null
    }
}
