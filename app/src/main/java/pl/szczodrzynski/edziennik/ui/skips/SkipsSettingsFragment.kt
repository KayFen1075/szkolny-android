/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.ui.skips

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.google.android.material.datepicker.MaterialDatePicker
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import android.widget.CheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.core.manager.SkipManager
import pl.szczodrzynski.edziennik.databinding.SkipsItemPlannedBinding
import pl.szczodrzynski.edziennik.databinding.SkipsSettingsFragmentBinding
import pl.szczodrzynski.edziennik.ext.dp
import pl.szczodrzynski.edziennik.ext.getSchoolYearConstrains
import pl.szczodrzynski.edziennik.ext.toDrawable
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.ext.onClick
import pl.szczodrzynski.edziennik.ui.base.fragment.BaseFragment

class SkipsSettingsFragment : BaseFragment<SkipsSettingsFragmentBinding, MainActivity>(
    inflater = SkipsSettingsFragmentBinding::inflate,
) {

    override fun getScrollingView() = b.scrollView

    private val config
        get() = app.profile.config.skips

    override suspend fun onViewReady(savedInstanceState: Bundle?) {
        b.goal.value = config.goalPercent.toFloat().coerceIn(1f, 99f)
        b.marginOk.value = config.marginOk.toFloat().coerceIn(1f, 40f)
        b.marginEdge.value = config.marginEdge.toFloat().coerceIn(0f, 39f)
        updateTexts()

        b.goal.onStop { config.goalPercent = it }
        b.marginOk.onStop {
            config.marginOk = it
            // keep "on the edge" below "can skip"
            if (config.marginEdge >= it) {
                config.marginEdge = it - 1
                b.marginEdge.value = (it - 1).toFloat()
            }
        }
        b.marginEdge.onStop {
            config.marginEdge = it
            if (config.marginOk <= it) {
                config.marginOk = it + 1
                b.marginOk.value = (it + 1).toFloat()
            }
        }

        b.belatedAsPresence.bind(config.belatedAsPresence) { config.belatedAsPresence = it }
        b.excusedAsPresence.bind(config.excusedAsPresence) { config.excusedAsPresence = it }
        b.showInTimetable.bind(config.showInTimetable) { config.showInTimetable = it }
        b.showInAttendance.bind(config.showInAttendance) { config.showInAttendance = it }
        b.showInHome.bind(config.showInHome) { config.showInHome = it }

        b.ignoredEventTypes.onClick {
            launch { pickEventTypes() }
        }

        b.addFreeDays.onClick {
            pickFreeDays()
        }
        app.skipManager.observeUpdates(this) {
            loadFreeDays()
        }
        loadFreeDays()
    }

    private fun loadFreeDays() {
        if (!isAdded)
            return
        val profile = app.profile
        val freeDays = app.skipManager.getFreeDays(profile)
            .filter { it.dateTo >= profile.dateSemester1Start && it.dateFrom <= profile.dateYearEnd }
            .sortedBy { it.dateFrom }
        val today = Date.getToday()

        b.freeDays.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (freeDay in freeDays) {
            val ib = SkipsItemPlannedBinding.inflate(inflater, b.freeDays, false)
            ib.title.text = freeDay.name ?: "-"
            val dates = if (freeDay.dateFrom == freeDay.dateTo)
                freeDay.dateFrom.formattedString
            else
                "${freeDay.dateFrom.formattedString} – ${freeDay.dateTo.formattedString}"
            val source = getString(when (freeDay.source) {
                SkipManager.FreeDaySource.REGISTER -> R.string.skips_free_days_source_register
                SkipManager.FreeDaySource.STATUTORY -> R.string.skips_free_days_source_statutory
                SkipManager.FreeDaySource.MANUAL -> R.string.skips_free_days_source_manual
            })
            ib.details.text = "$dates • $source"
            ib.statusIcon.setImageDrawable(
                CommunityMaterial.Icon.cmd_calendar_remove_outline.toDrawable(activity)
            )
            ib.root.alpha = if (freeDay.dateTo < today) 0.5f else 1f

            ib.remove.isVisible = freeDay.source == SkipManager.FreeDaySource.MANUAL
            ib.remove.setImageDrawable(CommunityMaterial.Icon.cmd_delete_outline.toDrawable(activity))
            ib.remove.onClick {
                app.skipManager.removeFreeDay(freeDay)
            }
            b.freeDays.addView(ib.root)
        }
    }

    private fun pickFreeDays() {
        MaterialDatePicker.Builder.dateRangePicker()
            .setCalendarConstraints(app.profile.getSchoolYearConstrains())
            .build()
            .apply {
                addOnPositiveButtonClickListener { selection ->
                    val dateFrom = Date.fromMillisUtc(selection.first ?: return@addOnPositiveButtonClickListener)
                    val dateTo = Date.fromMillisUtc(selection.second ?: selection.first)
                    askFreeDaysName(dateFrom, dateTo)
                }
            }
            .show(activity.supportFragmentManager, "SkipsSettingsFragment")
    }

    private fun askFreeDaysName(dateFrom: Date, dateTo: Date) {
        val input = EditText(activity).apply {
            setHint(R.string.skips_free_days_name_hint)
        }
        val container = FrameLayout(activity).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.skips_free_days_add)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                app.skipManager.addFreeDay(SkipManager.FreeDay(
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    name = input.text?.toString()?.trim()?.ifEmpty { null },
                    source = SkipManager.FreeDaySource.MANUAL,
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateTexts() {
        b.goalText.text = getString(R.string.skips_settings_goal, b.goal.value.toInt())
        b.marginOkText.text = getString(R.string.skips_settings_margin_ok, b.marginOk.value.toInt())
        b.marginEdgeText.text = getString(R.string.skips_settings_margin_edge, b.marginEdge.value.toInt())
    }

    private fun Slider.onStop(onChange: (value: Int) -> Unit) {
        addOnChangeListener { _, _, _ -> updateTexts() }
        addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                onChange(slider.value.toInt())
                updateTexts()
                app.skipManager.invalidate()
            }
        })
    }

    private fun CheckBox.bind(value: Boolean, onChange: (value: Boolean) -> Unit) {
        isChecked = value
        setOnCheckedChangeListener { _, isChecked ->
            onChange(isChecked)
            app.skipManager.invalidate()
        }
    }

    private suspend fun pickEventTypes() {
        val types = withContext(Dispatchers.IO) {
            app.db.eventTypeDao().getAllWithDefaults(app.profile)
        }.sortedBy { it.order }
        if (!isAdded)
            return
        val ignored = config.ignoredEventTypes.toSet()
        val names = types.map { it.name }.toTypedArray()
        // checked = the event type makes the lesson "not advised"
        val checked = types.map { it.id !in ignored }.toBooleanArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.skips_settings_event_types)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                config.ignoredEventTypes = types.filterIndexed { i, _ -> !checked[i] }.map { it.id }
                app.skipManager.invalidate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
