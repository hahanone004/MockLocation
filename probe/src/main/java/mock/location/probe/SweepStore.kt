package mock.location.probe

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The one place a run has to outlive the process that made it.
 *
 * Written just before the process is killed on purpose and read back by the
 * next one, which is what turns "the app was restarted" into a scenario that
 * can be compared with the rest rather than a run that starts over.
 */
object SweepStore {

    private const val FILE_NAME = "run.json"

    fun save(context: Context, results: Map<Scenario, Sweep>) {
        val document = JSONObject()
        results.forEach { (scenario, sweep) ->
            val readings = JSONObject()
            sweep.forEach { (id, reading) -> readings.put(id, reading.encode()) }
            document.put(scenario.name, readings)
        }

        runCatching { file(context).writeText(document.toString()) }
    }

    /**
     * The saved run, and nothing left behind: it describes the process that
     * has just been replaced, so reading it twice would compare a fresh run
     * against a stale one.
     */
    fun take(context: Context): Map<Scenario, Sweep>? {
        val file = file(context)
        if (!file.isFile) return null

        val loaded = runCatching {
            val document = JSONObject(file.readText())
            buildMap {
                for (name in document.keys()) {
                    val scenario = runCatching { Scenario.valueOf(name) }.getOrNull() ?: continue
                    val readings = document.getJSONObject(name)
                    put(
                        scenario,
                        buildMap {
                            for (id in readings.keys()) {
                                put(id, Reading.decode(readings.getString(id)))
                            }
                        },
                    )
                }
            }
        }.getOrNull()

        file.delete()
        return loaded
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
