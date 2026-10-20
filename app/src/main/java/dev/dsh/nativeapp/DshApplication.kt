package dev.dsh.nativeapp

import android.app.Application
import dev.dsh.nativeapp.data.DshClient
import dev.dsh.nativeapp.data.DshRepository
import dev.dsh.nativeapp.data.DshStreams
import dev.dsh.nativeapp.data.HostStore
import dev.dsh.nativeapp.data.PairedHost

/** Process-wide singletons; state that must survive Activity recreation lives here. */
class DshApplication : Application() {

    lateinit var hostStore: HostStore
        private set

    @Volatile
    private var clientRef: DshClient? = null

    @Volatile
    var currentHost: PairedHost? = null
        private set

    override fun onCreate() {
        super.onCreate()
        hostStore = HostStore(this)
        currentHost = hostStore.saved()
        currentHost?.let { attune(it) }
    }

    /** Point the shared client at a paired host. */
    fun attune(host: PairedHost) {
        currentHost = host
        val existing = clientRef
        if (existing == null) {
            clientRef = DshClient(host.baseUrl, host.cookie)
        } else {
            existing.baseUrl = host.baseUrl
            existing.cookieHeader = host.cookie
        }
    }

    fun unpair() {
        hostStore.clear()
        currentHost = null
        clientRef = null
    }

    val hasHost: Boolean get() = currentHost != null

    fun client(): DshClient = clientRef ?: error("no paired host")

    fun repository(): DshRepository = DshRepository(client())

    fun streams(): DshStreams = DshStreams(client())
}
