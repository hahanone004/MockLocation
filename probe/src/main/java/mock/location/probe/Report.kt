package mock.location.probe

import android.content.Context

/**
 * What every scenario said about one probe, and whether they agree.
 *
 * Agreement is the whole verdict. The probe cannot know what the profile says,
 * so it never claims a value is the spoofed one - only that a reading which
 * changed between a rotation and the reading before it has to have come from
 * somewhere the spoof does not cover. The reverse does not hold, and the
 * report says so: a feature that is switched off is perfectly stable.
 */
class Finding(val probe: Probe, val readings: Map<Scenario, Reading>) {

    enum class Verdict { STABLE, DRIFTED, THIN, NO_DATA }

    /** Each distinct value, and the scenarios that reported it. */
    val values: Map<String, List<Scenario>> = readings.entries
        .mapNotNull { (scenario, reading) -> reading.value?.let { it to scenario } }
        .groupBy({ it.first }, { it.second })

    /** Scenarios that had nothing to say, with the reason each gave. */
    val silent: Map<Scenario, String> = readings.mapNotNull { (scenario, reading) ->
        (reading as? Reading.Unavailable)?.let { scenario to it.reason }
    }.toMap()

    val verdict: Verdict = when {
        values.size > 1 -> Verdict.DRIFTED
        values.size == 1 && values.values.first().size > 1 -> Verdict.STABLE
        values.size == 1 -> Verdict.THIN
        else -> Verdict.NO_DATA
    }
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

    fun drifted(findings: List<Finding>): List<Finding> =
        findings.filter { it.verdict == Finding.Verdict.DRIFTED }

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

                when (finding.verdict) {
                    Finding.Verdict.DRIFTED ->
                        finding.values.forEach { (value, scenarios) ->
                            builder.append("  ! ").append(scenarioList(context, scenarios))
                                .append(": ").append(oneLine(value)).append('\n')
                        }

                    Finding.Verdict.STABLE, Finding.Verdict.THIN ->
                        finding.values.forEach { (value, scenarios) ->
                            builder.append("  = ").append(scenarioList(context, scenarios))
                                .append(": ").append(oneLine(value)).append('\n')
                        }

                    Finding.Verdict.NO_DATA ->
                        builder.append("  - ").append(context.getString(R.string.verdict_no_data))
                            .append('\n')
                }

                finding.silent.entries.groupBy({ it.value }, { it.key })
                    .forEach { (reason, scenarios) ->
                        builder.append("  - ").append(scenarioList(context, scenarios))
                            .append(": ").append(reason).append('\n')
                    }
            }
            builder.append('\n')
        }

        return builder.toString()
    }

    fun scenarioList(context: Context, scenarios: List<Scenario>): String =
        scenarios.joinToString("/") { context.getString(it.title) }

    /** Cell and scan-result readings are lists; keep a report line a line. */
    fun oneLine(value: String): String = value.replace('\n', ';')
}
