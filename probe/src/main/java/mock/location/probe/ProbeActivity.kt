package mock.location.probe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import mock.location.probe.databinding.ActivityProbeBinding

/**
 * The whole tool: start a run, watch the scenarios go by, read the report.
 *
 * The activity is deliberately not declared with configChanges, because two of
 * the scenarios are configuration changes and the run wants the real thing -
 * the activity torn down and built again the way the system does it, not a
 * callback the app handles itself.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeBinding
    private val adapter = FindingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.run.setOnClickListener { ProbeRun.start(this) }
        binding.restart.setOnClickListener { ProbeRun.restartProcess(this) }
        binding.copy.setOnClickListener { copyReport() }

        requestMissingPermissions()
        // Idempotent, and the call that matters when the permissions were
        // granted after the application had already tried.
        Watchers.start(this)
        render()
    }

    override fun onResume() {
        super.onResume()

        Watchers.start(this)
        ProbeRun.observe(::render)
        ProbeRun.resume(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        ProbeRun.observe(null)
    }

    private fun render() {
        val sweeps = ProbeRun.sweeps
        val findings = Report.of(sweeps)
        adapter.submit(findings)

        val drifted = findings.count { it.verdict == Finding.Verdict.DRIFTED }
        val stable = findings.count { it.verdict == Finding.Verdict.STABLE }

        binding.status.text = when (val step = ProbeRun.step) {
            null -> getString(
                if (ProbeRun.state == ProbeRun.State.DONE) R.string.status_done
                else R.string.status_idle,
                sweeps.size,
                drifted,
                stable,
            )

            else -> getString(R.string.status_running, getString(step.title), sweeps.size)
        }

        binding.run.setText(
            if (ProbeRun.sweeps.size > 1) R.string.action_run_again else R.string.action_run
        )
        binding.run.isEnabled = ProbeRun.state != ProbeRun.State.RUNNING
        binding.restart.isEnabled = ProbeRun.state != ProbeRun.State.RUNNING
    }

    private fun copyReport() {
        val report = Report.text(this, ProbeRun.sweeps)
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.report_title), report))

        Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * A refused permission is not a failed test - the report records it as a
     * reading that could not be taken - but a run with half its probes silent
     * says very little, and the cold reading is taken before this dialog can
     * possibly have been answered. So ask once, up front, and say plainly that
     * the first cold reading of a fresh install is the one to throw away.
     */
    private fun requestMissingPermissions() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return

        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        Toast.makeText(this, R.string.permissions_note, Toast.LENGTH_LONG).show()
    }

    private companion object {

        const val REQUEST_PERMISSIONS = 1

        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
        )
    }
}
