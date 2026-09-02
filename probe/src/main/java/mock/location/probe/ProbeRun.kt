package mock.location.probe

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process

/**
 * One pass over every scenario, and the results it has collected so far.
 *
 * A process-wide object rather than anything held by the activity, because two
 * of the scenarios destroy the activity and one of them destroys the process.
 * What survives which is the whole point: the run is kept here across a
 * recreate and a rotation, and written to disk across the kill.
 */
object ProbeRun {

    enum class State { IDLE, RUNNING, DONE }

    /** How long to wait for a configuration change that may never arrive. */
    private const val CONFIG_TIMEOUT_MILLIS = 2_500L

    /** Long enough for the task to actually be in the background. */
    private const val BACKGROUND_MILLIS = 2_500L

    private val main = Handler(Looper.getMainLooper())
    private val results = LinkedHashMap<Scenario, Sweep>()
    private val queue = ArrayDeque<Scenario>()

    /** The capture this run is waiting for the activity to come back for. */
    private class Awaited(val scenario: Scenario, val token: Long)

    private var awaited: Awaited? = null
    private var token = 0L
    private var remoteConnection: ServiceConnection? = null
    private var listener: (() -> Unit)? = null

    var state: State = State.IDLE
        private set

    /** The scenario in flight, for the status line. */
    var step: Scenario? = null
        private set

    val sweeps: Map<Scenario, Sweep> get() = results

    fun observe(callback: (() -> Unit)?) {
        listener = callback
    }

    private fun changed() {
        main.post { listener?.invoke() }
    }

    fun record(scenario: Scenario, sweep: Sweep) {
        results[scenario] = sweep
        changed()
    }

    /** Puts a run loaded from disk back, without disturbing what is here. */
    fun restore(saved: Map<Scenario, Sweep>) {
        saved.forEach { (scenario, sweep) -> results.putIfAbsent(scenario, sweep) }
        state = State.DONE
        changed()
    }

    fun clear() {
        results.clear()
        queue.clear()
        awaited = null
        step = null
        state = State.IDLE
        changed()
    }

    /**
     * Starts the sweep sequence. The cold reading is already in - the
     * application took it before any of this existed - and the one taken after
     * a process kill arrives the same way, so neither is queued here.
     */
    fun start(activity: Activity) {
        queue.clear()
        queue.addAll(
            listOf(
                Scenario.FOREGROUND,
                Scenario.WORKER,
                Scenario.RECREATE,
                Scenario.LANDSCAPE,
                Scenario.PORTRAIT,
                // Last two on purpose: the task is in the background from here
                // on, and neither of them needs an activity to be running.
                Scenario.BACKGROUND,
                Scenario.REMOTE,
            )
        )
        state = State.RUNNING
        advance(activity)
    }

    /**
     * Called from the activity every time it comes back, which is both how a
     * capture that was waiting on a configuration change is taken and how a run
     * that had nothing to run on picks up again.
     */
    fun resume(activity: Activity) {
        val pending = awaited
        if (pending != null) {
            awaited = null
            record(pending.scenario, Probes.sweep(activity))
            advance(activity)
            return
        }
        if (state == State.RUNNING) advance(activity)
    }

    /**
     * [context] is an Activity for as long as the queue holds a scenario that
     * needs one. Once it does not - after the task has gone to the background -
     * the application context carries the rest.
     */
    private fun advance(context: Context) {
        val next = queue.firstOrNull()
        if (next == null) {
            step = null
            state = State.DONE
            changed()
            return
        }
        if (next.needsActivity() && context !is Activity) return

        queue.removeFirst()
        step = next
        changed()

        when (next) {
            Scenario.FOREGROUND -> {
                record(next, Probes.sweep(context))
                advance(context)
            }

            Scenario.WORKER -> {
                val application = context.applicationContext
                Thread({
                    val sweep = Probes.sweep(application)
                    main.post {
                        record(next, sweep)
                        advance(context)
                    }
                }, "probe-worker").start()
            }

            Scenario.RECREATE -> await(next, context as Activity) { it.recreate() }

            Scenario.LANDSCAPE -> await(next, context as Activity) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

            Scenario.PORTRAIT -> await(next, context as Activity) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            Scenario.BACKGROUND -> runInBackground(context as Activity)

            Scenario.REMOTE -> runInRemoteProcess(context.applicationContext)

            Scenario.COLD, Scenario.RESTART -> advance(context)
        }
    }

    /**
     * Asks for a configuration change and captures what the activity reads once
     * it has come back.
     *
     * The timeout is what covers the case where the change never happens - the
     * device is already in that orientation, or rotation is locked. It carries
     * the token the capture was armed with, so an old timeout that fires after
     * the activity already came back finds the run past it and does nothing.
     */
    private fun await(scenario: Scenario, activity: Activity, trigger: (Activity) -> Unit) {
        val armed = ++token
        awaited = Awaited(scenario, armed)

        main.postDelayed({
            if (awaited?.token != armed) return@postDelayed
            awaited = null
            record(scenario, Probes.sweep(activity))
            advance(activity)
        }, CONFIG_TIMEOUT_MILLIS)

        trigger(activity)
    }

    /**
     * Reads the device while the task is not in the foreground.
     *
     * Coming back afterwards is a request, not a guarantee: an app in the
     * background may not start an activity. It does not have to work - the only
     * step left needs no activity, so the run finishes either way and the
     * report is waiting when the app is opened again.
     */
    private fun runInBackground(activity: Activity) {
        val application = activity.applicationContext
        activity.moveTaskToBack(true)

        main.postDelayed({
            record(Scenario.BACKGROUND, Probes.sweep(application))
            advance(application)

            runCatching {
                activity.startActivity(
                    Intent(application, ProbeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, BACKGROUND_MILLIS)
    }

    /** The same sweep, taken in a second process of this same app. */
    private fun runInRemoteProcess(application: Context) {
        val reply = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                record(Scenario.REMOTE, RemoteProbeService.decode(message.data))
                remoteConnection?.let { connection ->
                    runCatching { application.unbindService(connection) }
                }
                remoteConnection = null
                advance(application)
                true
            }
        )

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val request = Message.obtain(null, RemoteProbeService.MSG_SWEEP)
                request.replyTo = reply
                runCatching { Messenger(binder).send(request) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        remoteConnection = connection

        val bound = runCatching {
            application.bindService(
                Intent(application, RemoteProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!bound) {
            remoteConnection = null
            record(
                Scenario.REMOTE,
                Probes.all.associate {
                    it.id to Reading.Unavailable(application.getString(R.string.reading_no_remote))
                },
            )
            advance(application)
        }
    }

    /**
     * Saves the run and takes the process down with it. What comes back is a
     * process that has never been spoofed before, and its cold reading is the
     * last scenario.
     */
    fun restartProcess(context: Context) {
        SweepStore.save(context, results)
        Process.killProcess(Process.myPid())
    }

    private fun Scenario.needsActivity(): Boolean = when (this) {
        Scenario.RECREATE, Scenario.LANDSCAPE, Scenario.PORTRAIT, Scenario.BACKGROUND -> true
        else -> false
    }
}
