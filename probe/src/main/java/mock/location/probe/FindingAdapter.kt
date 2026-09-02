package mock.location.probe

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import mock.location.probe.databinding.ItemFindingBinding
import mock.location.probe.databinding.ItemGroupBinding

/** The report: a heading per feature, then one row per probe. */
class FindingAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val group: Group) : Row()
        data class Item(val finding: Finding) : Row()
    }

    private var rows: List<Row> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(findings: List<Finding>) {
        rows = buildList {
            Group.entries.forEach { group ->
                val inGroup = findings.filter { it.probe.group == group }
                if (inGroup.isEmpty()) return@forEach

                add(Row.Header(group))
                inGroup.forEach { add(Row.Item(it)) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGroupBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemFindingBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row.group)
            is Row.Item -> (holder as ItemHolder).bind(row.finding)
        }
    }

    private class HeaderHolder(
        private val binding: ItemGroupBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: Group) {
            binding.title.setText(group.title)
        }
    }

    private class ItemHolder(
        private val binding: ItemFindingBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(finding: Finding) {
            val context = binding.root.context

            binding.probe.text = finding.probe.id
            binding.notCovered.visibility =
                if (finding.probe.covered) View.GONE else View.VISIBLE

            val verdict = when (finding.verdict) {
                Finding.Verdict.DRIFTED -> R.string.verdict_drifted to R.color.verdict_drifted
                Finding.Verdict.STABLE -> R.string.verdict_stable to R.color.verdict_stable
                Finding.Verdict.THIN -> R.string.verdict_thin to R.color.verdict_thin
                Finding.Verdict.NO_DATA -> R.string.verdict_no_data to R.color.verdict_thin
            }
            binding.verdict.setText(verdict.first)
            binding.verdict.setTextColor(ContextCompat.getColor(context, verdict.second))

            val detail = StringBuilder()
            finding.values.forEach { (value, scenarios) ->
                if (detail.isNotEmpty()) detail.append('\n')
                detail.append(Report.scenarioList(context, scenarios))
                    .append(": ")
                    .append(value)
            }
            finding.silent.entries.groupBy({ it.value }, { it.key })
                .forEach { (reason, scenarios) ->
                    if (detail.isNotEmpty()) detail.append('\n')
                    detail.append(Report.scenarioList(context, scenarios))
                        .append(": ")
                        .append(reason)
                }

            binding.detail.text = detail
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
