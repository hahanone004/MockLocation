package mock.location.probe

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * The same sweep, taken in a second process.
 *
 * A hook installed at Application.attach runs once per process, and a profile
 * lookup that failed in one process says nothing about the other. An app with
 * a `:remote` service is ordinary enough that a spoof which only holds in the
 * main process is a real defect rather than a curiosity.
 */
class RemoteProbeService : Service() {

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            if (message.what == MSG_SWEEP) {
                val reply = Message.obtain(null, MSG_SWEEP)
                reply.data = encode(Probes.sweep(this))
                runCatching { message.replyTo?.send(reply) }
            }
            true
        }
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    companion object {

        const val MSG_SWEEP = 1

        fun encode(sweep: Sweep): Bundle = Bundle().apply {
            sweep.forEach { (id, reading) -> putString(id, reading.encode()) }
        }

        fun decode(data: Bundle?): Sweep {
            if (data == null) return emptyMap()

            return Probes.all.mapNotNull { probe ->
                data.getString(probe.id)?.let { probe.id to Reading.decode(it) }
            }.toMap()
        }
    }
}
