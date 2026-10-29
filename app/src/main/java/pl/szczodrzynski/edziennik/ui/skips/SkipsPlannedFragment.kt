/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.ui.skips

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.view.isVisible
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.core.manager.SkipManager
import pl.szczodrzynski.edziennik.databinding.SkipsItemPlannedBinding
import pl.szczodrzynski.edziennik.databinding.SkipsPlannedFragmentBinding
import pl.szczodrzynski.edziennik.ext.getSchoolYearConstrains
import pl.szczodrzynski.edziennik.ext.onClick
import pl.szczodrzynski.edziennik.ext.toDrawable
import pl.szczodrzynski.edziennik.ui.base.fragment.BaseFragment
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Week

class SkipsPlannedFragment : BaseFragment<SkipsPlannedFragmentBinding, MainActivity>(
    inflater = SkipsPlannedFragmentBinding::inflate,
) {
    companion object {
        private const val TAG = "SkipsPlannedFragment"
    }

    override fun getScrollingView() = b.scrollView

    private val manager
        get() = app.skipManager

    override suspend fun onViewReady(savedInstanceState: Bundle?) {
        b.planDay.onClick {
            pickDay()
        }
        manager.observeUpdates(this) {
            launch { load() }
        }
        load()
    }

    private suspend fun load() {
        val data = manager.getData(force = true)
        if (!isAdded)
            return

        b.noData.isVisible = data.planned.isEmpty()
        b.list.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (skip in data.planned) {
            val subject = data.subjects[skip.subjectId]
            val ib = SkipsItemPlannedBinding.inflate(inflater, b.list, false)
            ib.title.text = subject?.subjectName ?: "?"
            ib.details.text = listOf(
                Week.getFullDayName(skip.date.weekDay) + ", " + skip.date.formattedString,
                skip.startTime.stringHM,
            ).joinToString(" • ")

            val status = subject?.getStatus(alreadyPlanned = true)
            ib.statusIcon.setImageDrawable(
                CommunityMaterial.Icon3.cmd_run.toDrawable(
                    activity,
                    colorInt = status?.color ?: 0xff9e9e9e.toInt(),
                )
            )
            ib.remove.setImageDrawable(CommunityMaterial.Icon.cmd_delete_outline.toDrawable(activity))
            ib.remove.onClick {
                manager.setPlanned(listOf(skip), planned = false)
            }
            b.list.addView(ib.root)
        }
    }

    private fun pickDay() {
        val today = Date.getToday()
        MaterialDatePicker.Builder.datePicker()
            .setSelection(today.inMillisUtc)
            .setCalendarConstraints(app.profile.getSchoolYearConstrains())
            .build()
            .apply {
                addOnPositiveButtonClickListener { millis ->
                    val date = Date.fromMillisUtc(millis)
                    this@SkipsPlannedFragment.launch { pickLessons(date) }
                }
            }
            .show(activity.supportFragmentManager, TAG)
    }

    private suspend fun pickLessons(date: Date) {
        val data = manager.getData(force = true)
        val lessons = withContext(Dispatchers.IO) {
            app.db.timetableDao().getAllForDateNow(App.profileId, date)
        }.filter { data.isUpcoming(it) }
        if (!isAdded)
            return
        if (lessons.isEmpty()) {
            Toast.makeText(activity, R.string.skips_plan_day_no_lessons, Toast.LENGTH_SHORT).show()
            return
        }

        val names = lessons.map {
            "${it.displayStartTime?.stringHM ?: "?"}  ${it.displaySubjectName ?: "?"}"
        }.toTypedArray()
        // pre-select everything if nothing is planned on this day yet
        val anyPlanned = lessons.any { data.isPlanned(it) }
        val checked = lessons.map { !anyPlanned || data.isPlanned(it) }.toBooleanArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(Week.getFullDayName(date.weekDay) + ", " + date.formattedString)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val skips = lessons.mapNotNull { SkipManager.PlannedSkip.of(it) }
                manager.setPlanned(skips, planned = false)
                manager.setPlanned(
                    lessons.filterIndexed { i, _ -> checked[i] }.mapNotNull { SkipManager.PlannedSkip.of(it) },
                    planned = true,
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
