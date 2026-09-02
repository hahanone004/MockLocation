package mock.location.probe

import android.app.Application

/**
 * The cold reading, taken before anything else in the process exists.
 *
 * This is the earliest an ordinary app could possibly look, and it is where the
 * module's own install has to have finished: a spoof that arrives a moment
 * later is a spoof an app that reads the language in attachBaseContext will
 * never see. Everything else the run does is a comparison against this.
 */
class ProbeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // The remote process runs this same Application class. It takes its own
        // reading when it is asked to and has no run of its own to keep.
        // Application.getProcessName() is static, so it is named rather than
        // inherited into scope.
        if (Application.getProcessName().contains(':')) return

        val cold = Probes.sweep(this)
        val saved = SweepStore.take(this)

        if (saved == null) {
            ProbeRun.record(Scenario.COLD, cold)
            return
        }

        // A run was saved on the way out, so this process is the restart it
        // was waiting for, and its cold reading is the last scenario.
        ProbeRun.restore(saved)
        ProbeRun.record(Scenario.RESTART, cold)
    }
}
