/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.ui.skips

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.core.manager.SkipManager
import pl.szczodrzynski.edziennik.data.enums.FeatureType
import pl.szczodrzynski.edziennik.databinding.SkipsItemSubjectBinding
import pl.szczodrzynski.edziennik.databinding.SkipsOverviewFragmentBinding
import pl.szczodrzynski.edziennik.databinding.SkipsSubjectDialogBinding
import pl.szczodrzynski.edziennik.ui.base.fragment.BaseFragment

class SkipsOverviewFragment : BaseFragment<SkipsOverviewFragmentBinding, MainActivity>(
    inflater = SkipsOverviewFragmentBinding::inflate,
) {

    override fun getScrollingView() = b.scrollView
    override fun getSyncParams() = FeatureType.ATTENDANCE to null

    private val manager
        get() = app.skipManager

    override suspend fun onViewReady(savedInstanceState: Bundle?) {
        // reload after syncing the attendance or the timetable
        app.db.attendanceDao().getAll(App.profileId).observe(this) {
            launch { load() }
        }
        manager.observeUpdates(this) {
            launch { load() }
        }
    }

    private suspend fun load() {
        val data = manager.getData(force = true)
        if (!isAdded)
            return
        b.progressBar.isVisible = false

        // subjects below the goal first, then by the status, then by the name
        val subjects = data.subjects.values.sortedWith(
            compareBy<SkipManager.SubjectStats>(
                { it.isExcluded },
                { !it.hasData },
                { -(it.getStatus()?.ordinal ?: -1) },
                { it.subjectName.lowercase() },
            )
        )
        if (subjects.isEmpty()) {
            b.noData.isVisible = true
            b.scrollView.isVisible = false
            return
        }
        b.noData.isVisible = false
        b.scrollView.isVisible = true

        val config = app.profile.config.skips
        b.summaryPercent.text = data.percent?.let { getString(R.string.skips_percent_format, it) } ?: "-"
        b.summaryDetails.text = listOfNotNull(
            data.percentPlanned?.let { getString(R.string.skips_overview_with_planned, it) },
            getString(R.string.skips_overview_goal, config.goalPercent),
            getString(
                R.string.skips_overview_below_goal,
                subjects.count { !it.isExcluded && it.hasData && it.canSkip < 0 },
            ),
            getString(R.string.skips_overview_planned, data.planned.size),
        ).joinToString("\n")

        b.subjects.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (subject in subjects) {
            val ib = SkipsItemSubjectBinding.inflate(inflater, b.subjects, false)
            bindSubject(ib, subject)
            ib.root.setOnClickListener {
                showSubjectDialog(subject)
            }
            b.subjects.addView(ib.root)
        }
    }

    private fun bindSubject(ib: SkipsItemSubjectBinding, subject: SkipManager.SubjectStats) {
        ib.title.text = subject.subjectName
        ib.root.alpha = if (subject.isExcluded) 0.5f else 1f

        val status = subject.getStatus()
        ib.statusIcon.isVisible = status != null
        ib.statusIcon.setImageDrawable(status?.toDrawable(activity))

        val percent = subject.percentPlanned ?: subject.percent
        ib.percent.text = percent?.let { getString(R.string.skips_percent_format, it) } ?: "-"
        ib.progress.progress = percent?.toInt() ?: 0
        if (status != null)
            ib.progress.setIndicatorColor(status.color)

        val lines = mutableListOf<String>()
        if (subject.isExcluded) {
            lines += getString(R.string.skips_subject_is_excluded)
        } else if (!subject.hasData) {
            lines += getString(R.string.skips_no_data)
        } else {
            val goal = getString(
                if (subject.isCustomGoal) R.string.skips_item_goal_custom else R.string.skips_item_goal,
                subject.goal,
            )
            val skip = when {
                status == SkipManager.Status.DOOMED -> getString(R.string.skips_item_doomed)
                status == SkipManager.Status.CHILL -> getString(R.string.skips_item_chill)
                subject.canSkip >= 0 -> getString(R.string.skips_item_can_skip, subject.canSkip)
                else -> getString(R.string.skips_item_must_attend, subject.mustAttend)
            }
            lines += "$goal • $skip"
            lines += listOfNotNull(
                getString(R.string.skips_item_attended, subject.presence, subject.total),
                getString(
                    if (subject.remainingEstimated) R.string.skips_item_remaining_estimated else R.string.skips_item_remaining,
                    subject.remaining,
                ),
                if (subject.planned > 0) getString(R.string.skips_item_planned, subject.planned) else null,
            ).joinToString(" • ")
        }
        ib.details.text = lines.joinToString("\n")
    }

    private fun showSubjectDialog(subject: SkipManager.SubjectStats) {
        val db = SkipsSubjectDialogBinding.inflate(LayoutInflater.from(activity))
        db.excluded.isChecked = subject.isExcluded
        db.customGoal.isChecked = subject.isCustomGoal
        db.goal.value = subject.goal.toFloat().coerceIn(1f, 99f)

        fun updateGoal() {
            db.goal.isEnabled = db.customGoal.isChecked
            db.goalText.text = getString(R.string.skips_settings_goal, db.goal.value.toInt())
        }
        updateGoal()
        db.customGoal.setOnCheckedChangeListener { _, _ -> updateGoal() }
        db.goal.addOnChangeListener { _, _, _ -> updateGoal() }

        MaterialAlertDialogBuilder(activity)
            .setTitle(subject.subjectName)
            .setView(db.root)
            .setPositiveButton(R.string.save) { _, _ ->
                manager.setSubjectExcluded(subject.subjectId, db.excluded.isChecked)
                manager.setSubjectGoal(
                    subject.subjectId,
                    if (db.customGoal.isChecked) db.goal.value.toInt() else null,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
