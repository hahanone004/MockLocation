package mock.location.probe

import androidx.annotation.StringRes

/**
 * The circumstances a sweep is taken under.
 *
 * Each one is a place a spoof has been seen to slip, rather than a variation
 * for its own sake: a different process, a different thread, a configuration
 * the system pushed in after the app started, and a fresh process reading the
 * same device a second time. What matters is not the value any single one of
 * them reports - the probe has no way of knowing what the profile says - but
 * that they all report the same thing. A hook that covers one entry point and
 * misses another shows up here as two scenarios that disagree.
 */
enum class Scenario(@StringRes val title: Int) {

    /** Taken in Application.onCreate, before any activity exists. */
    COLD(R.string.scenario_cold),

    /** The activity, on the main thread, in the foreground. */
    FOREGROUND(R.string.scenario_foreground),

    /** A background thread; some hooks hold per-thread state. */
    WORKER(R.string.scenario_worker),

    /** After Activity.recreate(). */
    RECREATE(R.string.scenario_recreate),

    /** After a configuration change the system pushed in: landscape. */
    LANDSCAPE(R.string.scenario_landscape),

    /** And back again. */
    PORTRAIT(R.string.scenario_portrait),

    /** While the task sits in the background. */
    BACKGROUND(R.string.scenario_background),

    /** In a second process of the same app. */
    REMOTE(R.string.scenario_remote),

    /** The cold read of the next process, after this one was killed. */
    RESTART(R.string.scenario_restart),
}
