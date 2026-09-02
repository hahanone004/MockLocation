package mock.location.probe

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What every scenario said about one probe, and whether they agree.
 *
 * Agreement is the whole verdict. The probe cannot know what the profile says,
 * so it never claims a value is the spoofed one - only that a reading which
 * changed between a rotation and the reading before it has to have come from
 * somewhere the spoof does not cover. The reverse does not hold, and the
 * report says so: a feature that is switched off is perfectly stable.
 *
 * Readings are gathered into clusters of answers that agree with each other.
 * For an exact probe a cluster is one distinct string. For a position it is a
 * group of fixes close enough together to be the same place, because a profile
 * with a jitter radius hands out a different point every time on purpose.
 */
class Finding(val probe: Probe, val readings: Map<Scenario, Reading>) {

    enum class Verdict { STABLE, DRIFTED, THIN, NO_DATA }

    /** One answer, and the scenarios that gave it. */
    class Cluster(
        val label: String,
        val scenarios: List<Scenario>,
        /** How far apart the fixes inside it are; null unless it is a position. */
        val spreadMetres: Int?,
    )

    private val points: Map<Scenario, String> = readings.mapNotNull { (scenario, reading) ->
        reading.value?.let { scenario to it }
    }.toMap()

    val clusters: List<Cluster> =
        if (probe.comparison == Comparison.COORDINATE) clusterByDistance() else clusterByText()

    /** How far apart the clusters are, for a position that landed in two places. */
    val separationMetres: Int? = separation()

    /** Scenarios that had nothing to say, with the reason each gave. */
    val silent: Map<Scenario, String> = readings.mapNotNull { (scenario, reading) ->
        (reading as? Reading.Unavailable)?.let { scenario to it.reason }
    }.toMap()

    val verdict: Verdict = when {
        clusters.size > 1 -> Verdict.DRIFTED
        clusters.size == 1 && clusters.first().scenarios.size > 1 -> Verdict.STABLE
        clusters.size == 1 -> Verdict.THIN
        else -> Verdict.NO_DATA
    }

    private fun clusterByText(): List<Cluster> = points.entries
        .groupBy({ it.value }, { it.key })
        .map { (value, scenarios) -> Cluster(value, scenarios, spreadMetres = null) }

    /**
     * Fixes that are near each other are the same answer.
     *
     * The threshold is not a claim about how much jitter a profile uses; it is
     * the gap between the two things worth telling apart. A jitter radius is
     * tens or hundreds of metres, while a leaked real position is somewhere
     * else entirely. The measured spread is reported alongside every cluster,
     * so a run that sits anywhere near the line says so out loud rather than
     * hiding behind the verdict.
     */
    private fun clusterByDistance(): List<Cluster> {
        val parsed = points.mapNotNull { (scenario, text) ->
            parse(text)?.let { scenario to it }
        }
        // Anything that will not parse is compared as text rather than dropped.
        if (parsed.size != points.size) return clusterByText()

        val groups = mutableListOf<MutableList<Pair<Scenario, Point>>>()
        parsed.forEach { entry ->
            val home = groups.firstOrNull { group ->
                metresBetween(centroid(group.map { it.second }), entry.second) <= SAME_PLACE_METRES
            }
            if (home == null) groups.add(mutableListOf(entry)) else home.add(entry)
        }

        return groups.map { group ->
            val middle = centroid(group.map { it.second })
            Cluster(
                label = format(middle),
                scenarios = group.map { it.first },
                spreadMetres = group.maxOf { metresBetween(middle, it.second) }.roundToInt(),
            )
        }
    }

    private fun separation(): Int? {
        if (probe.comparison != Comparison.COORDINATE || clusters.size < 2) return null

        val middles = clusters.mapNotNull { parse(it.label) }
        if (middles.size != clusters.size) return null

        var widest = 0.0
        for (first in middles.indices) {
            for (second in first + 1 until middles.size) {
                widest = maxOf(widest, metresBetween(middles[first], middles[second]))
            }
        }
        return widest.roundToInt()
    }

    private companion object {

        /** Nearer than this counts as the same place. */
        const val SAME_PLACE_METRES = 1_000.0
    }
}

private typealias Point = Pair<Double, Double>

private fun parse(text: String): Point? {
    val parts = text.split(',')
    if (parts.size != 2) return null

    val latitude = parts[0].trim().toDoubleOrNull() ?: return null
    val longitude = parts[1].trim().toDoubleOrNull() ?: return null

    return latitude to longitude
}

private fun format(point: Point): String =
    String.format(Locale.ROOT, "%.6f, %.6f", point.first, point.second)

/**
 * A plain mean. The points in one cluster are metres apart, so nothing here
 * needs to survive a wrap around the antimeridian - and a pair that did would
 * land in two clusters, which is the honest answer for a position that jumped
 * half the world.
 */
private fun centroid(points: List<Point>): Point =
    points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size

private fun metresBetween(from: Point, to: Point): Double {
    val latitudeDelta = Math.toRadians(to.first - from.first)
    val longitudeDelta = Math.toRadians(to.second - from.second)
    val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
        cos(Math.toRadians(from.first)) * cos(Math.toRadians(to.first)) *
        sin(longitudeDelta / 2) * sin(longitudeDelta / 2)

    return 6_371_000.0 * 2 * atan2(sqrt(abs(a)), sqrt(abs(1 - a)))
}

object Report {

    fun of(sweeps: Map<Scenario, Sweep>): List<Finding> = Probes.all.map { probe ->
        Finding(
            probe,
            sweeps.mapNotNull { (scenario, sweep) ->
                sweep[probe.id]?.let { scenario to it }
            }.toMap(),
        )
    }

    /** The whole run as text, for pasting into a bug report. */
    fun text(context: Context, sweeps: Map<Scenario, Sweep>): String {
        val findings = of(sweeps)
        val builder = StringBuilder()

        builder.append(context.getString(R.string.report_title)).append('\n')
        builder.append(
            context.getString(
                R.string.report_counts,
                findings.count { it.verdict == Finding.Verdict.DRIFTED },
                findings.count { it.verdict == Finding.Verdict.STABLE },
                sweeps.size,
            )
        ).append("\n\n")

        Group.entries.forEach { group ->
            val inGroup = findings.filter { it.probe.group == group }
            if (inGroup.isEmpty()) return@forEach

            builder.append("== ").append(context.getString(group.title)).append(" ==\n")
            inGroup.forEach { finding ->
                builder.append(finding.probe.id)
                if (!finding.probe.covered) {
                    builder.append(" [").append(context.getString(R.string.not_covered)).append(']')
                }
                builder.append('\n')

                // A single reading is not agreement, and printing it with the
                // same mark as eight that agree says it is.
                val mark = when (finding.verdict) {
                    Finding.Verdict.DRIFTED -> "  ! "
                    Finding.Verdict.THIN -> "  ? "
                    else -> "  = "
                }
                finding.clusters.forEach { cluster ->
                    builder.append(mark).append(describe(context, cluster)).append('\n')
                }
                finding.separationMetres?.let {
                    builder.append("    ")
                        .append(context.getString(R.string.cluster_separation, it))
                        .append('\n')
                }

                finding.silent.entries.groupBy({ it.value }, { it.key })
                    .forEach { (reason, scenarios) ->
                        builder.append("  - ").append(scenarioList(context, scenarios))
                            .append(": ").append(Reason.text(context, reason)).append('\n')
                    }
            }
            builder.append('\n')
        }

        return builder.toString()
    }

    /** One cluster on one line: who said it, what it was, how tightly. */
    fun describe(context: Context, cluster: Finding.Cluster): String {
        val line = StringBuilder()
            .append(scenarioList(context, cluster.scenarios))
            .append(": ")
            .append(oneLine(cluster.label))

        cluster.spreadMetres?.let {
            line.append(' ').append(context.getString(R.string.cluster_spread, it))
        }

        return line.toString()
    }

    fun scenarioList(context: Context, scenarios: List<Scenario>): String =
        scenarios.joinToString("/") { context.getString(it.title) }

    /** Cell and scan-result readings are lists; keep a report line a line. */
    fun oneLine(value: String): String = value.replace('\n', ';')
}
