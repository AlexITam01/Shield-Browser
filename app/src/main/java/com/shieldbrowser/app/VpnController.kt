package com.shieldbrowser.app

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Thin wrapper around WireGuard's GoBackend (a real VPN: needs a WireGuard config
 * from your own server or a provider such as Mullvad or Proton).
 *
 * The backend is created lazily so the native library is only loaded if you use the VPN.
 */
object VpnController {

    private var backend: GoBackend? = null

    private val tunnel = object : Tunnel {
        override fun getName(): String = "shield"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    @Synchronized
    private fun obtain(ctx: Context): GoBackend {
        val existing = backend
        if (existing != null) return existing
        val created = GoBackend(ctx.applicationContext)
        backend = created
        return created
    }

    /** Throws if the text is not a valid WireGuard config. */
    fun parse(text: String): Config =
        Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    /** Blocking: call from a background thread, after VpnService.prepare() consent. */
    fun connect(ctx: Context, file: File) {
        val config = parse(file.readText())
        obtain(ctx).setState(tunnel, Tunnel.State.UP, config)
    }

    /** Blocking: call from a background thread. */
    fun disconnect(ctx: Context) {
        obtain(ctx).setState(tunnel, Tunnel.State.DOWN, null)
    }

    fun isUp(): Boolean {
        val b = backend ?: return false
        return try {
            b.getState(tunnel) == Tunnel.State.UP
        } catch (e: Exception) {
            false
        }
    }
}
