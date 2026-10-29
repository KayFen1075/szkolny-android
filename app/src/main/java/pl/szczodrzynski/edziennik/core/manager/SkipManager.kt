/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.core.manager

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.mikepenz.iconics.typeface.IIcon
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.db.entity.Attendance
import pl.szczodrzynski.edziennik.data.db.entity.Lesson
import pl.szczodrzynski.edziennik.data.db.entity.Profile
import pl.szczodrzynski.edziennik.data.db.full.LessonFull
import pl.szczodrzynski.edziennik.ext.asJsonObjectList
import pl.szczodrzynski.edziennik.ext.getJsonArray
import pl.szczodrzynski.edziennik.ext.getString
import pl.szczodrzynski.edziennik.ext.toDrawable
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Time
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Calculates whether a lesson can be skipped, based on the attendance
 * percentage goal set by the user and the planned skips.
 *
 * All calculations cover the current semester only.
 */
class SkipManager(val app: App) {
    companion object {
        private const val CACHE_TTL = 5_000L
        private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val TIME_REGEX = Regex("\\d{6}")
        /**
         * How far back to look for lessons without the attendance entered yet.
         */
        private const val PENDING_LESSONS_DAYS = 14

        /**
         * Gregorian Easter Sunday (Meeus/Jones/Butcher algorithm).
         */
        fun getEasterDate(year: Int): Date {
            val a = year % 19
            val b = year / 100
            val c = year % 100
            val d = b / 4
            val e = b % 4
            val f = (b + 8) / 25
            val g = (b - f + 1) / 3
            val h = (19 * a + b - d - g + 15) % 30
            val i = c / 4
            val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val m = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * m + 114) / 31
            val day = (h + l - 7 * m + 114) % 31 + 1
            return Date(year, month, day)
        }
    }

    /**
     * Statuses ordered from the best to the worst one.
     */
    enum class Status(
        val icon: IIcon,
        val color: Int,
        @StringRes val nameRes: Int,
    ) {
        CHILL(CommunityMaterial.Icon.cmd_emoticon_cool_outline, 0xff2196f3.toInt(), R.string.skips_status_chill),
        OK(CommunityMaterial.Icon.cmd_check_circle_outline, 0xff43a047.toInt(), R.string.skips_status_ok),
        UNWANTED(CommunityMaterial.Icon.cmd_alert_outline, 0xffffb300.toInt(), R.string.skips_status_unwanted),
        EDGE(CommunityMaterial.Icon.cmd_alert_circle_outline, 0xffff6d00.toInt(), R.string.skips_status_edge),
        FORBIDDEN(CommunityMaterial.Icon.cmd_cancel, 0xffe53935.toInt(), R.string.skips_status_forbidden),
        DOOMED(CommunityMaterial.Icon3.cmd_skull_outline, 0xff9e9e9e.toInt(), R.string.skips_status_doomed);

        fun toDrawable(context: Context, sizeDp: Int = 24) =
            icon.toDrawable(context, colorInt = color, sizeDp = sizeDp)
    }

    data class PlannedSkip(
        val date: Date,
        val startTime: Time,
        val subjectId: Long,
    ) {
        companion object {
            fun parse(value: String): PlannedSkip? {
                val parts = value.split("|")
                if (parts.size != 3 || !parts[0].matches(DATE_REGEX) || !parts[1].matches(TIME_REGEX))
                    return null
                return PlannedSkip(
                    date = Date.fromY_m_d(parts[0]),
                    startTime = Time.fromHms(parts[1]),
                    subjectId = parts[2].toLongOrNull() ?: return null,
                )
            }

            fun of(lesson: LessonFull): PlannedSkip? {
                return PlannedSkip(
                    date = lesson.displayDate ?: return null,
                    startTime = lesson.displayStartTime ?: return null,
                    subjectId = lesson.displaySubjectId ?: return null,
                )
            }
        }

        fun serialize() = "${date.stringY_m_d}|${startTime.stringValue}|$subjectId"

        fun matches(date: Date, startTime: Time?) =
            this.date == date && this.startTime == startTime
    }

    class SubjectStats(
        val subjectId: Long,
        val subjectName: String,
        val goal: Int,
        val isCustomGoal: Boolean,
        val isExcluded: Boolean,
        val presence: Int,
        val total: Int,
        val planned: Int,
        val remaining: Int,
        val remainingEstimated: Boolean,
        /**
         * Whether the timetable is downloaded at all - otherwise
         * [remaining] is unknown, so "chill" and "doomed" can't be determined.
         */
        val remainingKnown: Boolean,
        private val marginOk: Int,
        private val marginEdge: Int,
    ) {
        val hasData
            get() = total > 0

        val percent: Float?
            get() = if (total == 0) null else presence * 100f / total

        val percentPlanned: Float?
            get() = if (total + planned == 0) null else presence * 100f / (total + planned)

        /**
         * How many more lessons can be skipped (on top of the planned ones)
         * to stay at or above the goal. Negative if the goal is already missed.
         */
        val canSkip: Int
            get() = floor((100.0 * presence - goal * (total + planned)) / goal).toInt()

        /**
         * How many lessons need to be attended (without any more skips)
         * to get back to the goal.
         */
        val mustAttend: Int
            get() {
                val missing = goal * (total + planned) - 100.0 * presence
                if (missing <= 0)
                    return 0
                return ceil(missing / (100 - goal)).toInt()
            }

        /**
         * Whether the goal is reached even when skipping every remaining lesson.
         */
        val isChill: Boolean
            get() = remainingKnown && (total > 0 || remaining > 0)
                    && 100L * presence >= goal.toLong() * (total + remaining)

        /**
         * Whether the goal can't be reached anymore, even when attending
         * every remaining lesson (and cancelling the planned skips).
         */
        val isDoomed: Boolean
            get() = remainingKnown && total > 0
                    && 100L * (presence + remaining) < goal.toLong() * (total + remaining)

        /**
         * Percentage after skipping one more lesson (or after the planned skips,
         * if [alreadyPlanned] is true).
         */
        fun percentAfterSkip(alreadyPlanned: Boolean = false): Float? {
            val count = total + planned + if (alreadyPlanned) 0 else 1
            if (count == 0)
                return null
            return presence * 100f / count
        }

        fun getStatus(alreadyPlanned: Boolean = false, hasEvent: Boolean = false): Status? {
            if (isExcluded || !hasData)
                return null
            if (isDoomed)
                return Status.DOOMED
            val status = if (isChill) {
                Status.CHILL
            } else {
                val after = percentAfterSkip(alreadyPlanned) ?: return null
                val margin = after - goal
                when {
                    margin < 0 -> Status.FORBIDDEN
                    margin < marginEdge -> Status.EDGE
                    margin < marginOk -> Status.UNWANTED
                    else -> Status.OK
                }
            }
            if (hasEvent && status < Status.UNWANTED)
                return Status.UNWANTED
            return status
        }
    }

    class SkipData(
        val profileId: Int,
        val semester: Int,
        val semesterStart: Date,
        val semesterEnd: Date,
        val subjects: Map<Long, SubjectStats>,
        val planned: List<PlannedSkip>,
        val freeDays: List<FreeDay>,
        private val eventKeys: Set<String>,
    ) {
        /**
         * Overall percentage of all non-excluded subjects.
         */
        val percent: Float?
            get() {
                val counted = subjects.values.filter { !it.isExcluded }
                val total = counted.sumOf { it.total }
                if (total == 0)
                    return null
                return counted.sumOf { it.presence } * 100f / total
            }

        val percentPlanned: Float?
            get() {
                val counted = subjects.values.filter { !it.isExcluded }
                val total = counted.sumOf { it.total + it.planned }
                if (total == 0)
                    return null
                return counted.sumOf { it.presence } * 100f / total
            }

        fun isPlanned(lesson: LessonFull): Boolean {
            val date = lesson.displayDate ?: return false
            val startTime = lesson.displayStartTime ?: return false
            return planned.any { it.matches(date, startTime) }
        }

        fun hasEvent(lesson: LessonFull): Boolean {
            val date = lesson.displayDate ?: return false
            val subjectId = lesson.displaySubjectId ?: return false
            val startTime = lesson.displayStartTime
            return "${date.value}|$subjectId|*" in eventKeys
                    || "${date.value}|$subjectId|${startTime?.value}" in eventKeys
                    || "${date.value}|-1|${startTime?.value}" in eventKeys
                    || "${date.value}|-1|*" in eventKeys
        }

        /**
         * Whether a skip status makes sense for this lesson,
         * i.e. it is not cancelled and it has not started yet.
         */
        fun isUpcoming(lesson: LessonFull): Boolean {
            if (lesson.isCancelled || lesson.type == Lesson.TYPE_NO_LESSONS)
                return false
            val date = lesson.displayDate ?: return false
            val startTime = lesson.displayStartTime ?: return false
            if (lesson.displaySubjectId == null)
                return false
            val today = Date.getToday()
            if (date > semesterEnd || date < today)
                return false
            if (freeDays.any { it.contains(date) })
                return false
            return date > today || startTime > Time.getNow()
        }

        fun getStatus(lesson: LessonFull): Status? {
            if (!isUpcoming(lesson))
                return null
            val subject = subjects[lesson.displaySubjectId] ?: return null
            return subject.getStatus(
                alreadyPlanned = isPlanned(lesson),
                hasEvent = hasEvent(lesson),
            )
        }
    }

    enum class FreeDaySource {
        REGISTER,
        STATUTORY,
        MANUAL,
    }

    data class FreeDay(
        val dateFrom: Date,
        val dateTo: Date,
        val name: String?,
        val source: FreeDaySource,
    ) {
        companion object {
            fun parse(value: String): FreeDay? {
                val parts = value.split("|", limit = 3)
                if (parts.size != 3 || !parts[0].matches(DATE_REGEX) || !parts[1].matches(DATE_REGEX))
                    return null
                return FreeDay(
                    dateFrom = Date.fromY_m_d(parts[0]),
                    dateTo = Date.fromY_m_d(parts[1]),
                    name = parts[2].ifEmpty { null },
                    source = FreeDaySource.MANUAL,
                )
            }
        }

        fun serialize() = "${dateFrom.stringY_m_d}|${dateTo.stringY_m_d}|${name ?: ""}"

        fun contains(date: Date) = date >= dateFrom && date <= dateTo
    }

    @Volatile
    private var cache: SkipData? = null
    @Volatile
    private var cacheTime = 0L

    /**
     * Emits whenever the skip config or the planned skips change,
     * so that the views can be refreshed.
     */
    val updates = MutableLiveData<Long>()

    fun invalidate() {
        cache = null
        updates.postValue(System.currentTimeMillis())
    }

    /**
     * Observe [updates], ignoring the value present at the time of subscribing.
     */
    fun observeUpdates(owner: LifecycleOwner, onUpdate: () -> Unit) {
        val initial = updates.value
        updates.observe(owner) {
            if (it != initial)
                onUpdate()
        }
    }

    /**
     * Get the icon to show next to the lesson - a running man if the skip
     * is planned, the status icon otherwise. Both are colored by the status.
     */
    fun getLessonDrawable(context: Context, data: SkipData, lesson: LessonFull, sizeDp: Int = 24): Drawable? {
        val status = data.getStatus(lesson)
        return when {
            data.isUpcoming(lesson) && data.isPlanned(lesson) ->
                CommunityMaterial.Icon3.cmd_run.toDrawable(
                    context,
                    colorInt = status?.color ?: 0xff9e9e9e.toInt(),
                    sizeDp = sizeDp,
                )
            status != null -> status.toDrawable(context, sizeDp)
            else -> null
        }
    }

    /**
     * Get all free days of the school year: downloaded from the e-register,
     * the statutory holidays/breaks and the ones added manually.
     */
    fun getFreeDays(profile: Profile): List<FreeDay> {
        val result = mutableListOf<FreeDay>()
        for (key in listOf("schoolFreeDays", "classFreeDays")) {
            profile.studentData.getJsonArray(key)?.asJsonObjectList()?.forEach {
                val dateFrom = it.getString("dateFrom") ?: return@forEach
                result += FreeDay(
                    dateFrom = Date.fromY_m_d(dateFrom),
                    dateTo = Date.fromY_m_d(it.getString("dateTo") ?: dateFrom),
                    name = it.getString("name"),
                    source = FreeDaySource.REGISTER,
                )
            }
        }
        result += getStatutoryFreeDays(profile.studentSchoolYearStart)
        result += profile.config.skips.freeDays.mapNotNull { FreeDay.parse(it) }
        return result
    }

    /**
     * Public holidays and statutory school breaks in Poland.
     * Winter breaks depend on the voivodeship, so they are not included.
     */
    private fun getStatutoryFreeDays(yearStart: Int): List<FreeDay> {
        val yearEnd = yearStart + 1
        val easter = getEasterDate(yearEnd)
        fun day(date: Date, @StringRes name: Int) = FreeDay(date, date, app.getString(name), FreeDaySource.STATUTORY)
        return listOf(
            day(Date(yearStart, 11, 1), R.string.skips_free_day_all_saints),
            day(Date(yearStart, 11, 11), R.string.skips_free_day_independence),
            FreeDay(
                Date(yearStart, 12, 23), Date(yearStart, 12, 31),
                app.getString(R.string.skips_free_day_christmas_break), FreeDaySource.STATUTORY,
            ),
            day(Date(yearEnd, 1, 1), R.string.skips_free_day_new_year),
            day(Date(yearEnd, 1, 6), R.string.skips_free_day_epiphany),
            FreeDay(
                easter.plusDays(-3), easter.plusDays(2),
                app.getString(R.string.skips_free_day_easter_break), FreeDaySource.STATUTORY,
            ),
            day(Date(yearEnd, 5, 1), R.string.skips_free_day_labour),
            day(Date(yearEnd, 5, 3), R.string.skips_free_day_constitution),
            day(easter.plusDays(60), R.string.skips_free_day_corpus_christi),
        )
    }

    fun addFreeDay(freeDay: FreeDay, profile: Profile = app.profile) {
        val config = profile.config.skips
        config.freeDays = config.freeDays + freeDay.serialize()
        invalidate()
    }

    fun removeFreeDay(freeDay: FreeDay, profile: Profile = app.profile) {
        val config = profile.config.skips
        config.freeDays = config.freeDays - freeDay.serialize()
        invalidate()
    }

    fun getSemesterRange(profile: Profile): Pair<Date, Date> {
        val today = Date.getToday()
        return if (today >= profile.dateSemester2Start)
            profile.dateSemester2Start to profile.dateYearEnd
        else
            profile.dateSemester1Start to profile.dateSemester2Start.clone().stepForward(0, 0, -1)
    }

    suspend fun getData(profile: Profile = app.profile, force: Boolean = false): SkipData {
        val cached = cache
        if (!force && cached != null && cached.profileId == profile.id
            && System.currentTimeMillis() - cacheTime < CACHE_TTL
        )
            return cached
        return withContext(Dispatchers.IO) {
            buildData(profile)
        }.also {
            cache = it
            cacheTime = System.currentTimeMillis()
        }
    }

    private fun isPresence(baseType: Int, belatedAsPresence: Boolean, excusedAsPresence: Boolean) =
        when (baseType) {
            Attendance.TYPE_PRESENT,
            Attendance.TYPE_PRESENT_CUSTOM,
            Attendance.TYPE_RELEASED -> true
            Attendance.TYPE_BELATED,
            Attendance.TYPE_BELATED_EXCUSED -> belatedAsPresence
            Attendance.TYPE_ABSENT_EXCUSED -> excusedAsPresence
            else -> false
        }

    private fun buildData(profile: Profile): SkipData {
        val config = profile.config.skips
        val profileId = profile.id
        val semester = profile.currentSemester
        val (semesterStart, semesterEnd) = getSemesterRange(profile)
        val today = Date.getToday()
        val now = Time.getNow()

        // count the attendance
        val attendance = app.db.attendanceDao().getAllNow(profileId)
            .filter { it.semester == semester }
        val subjectNames = mutableMapOf<Long, String>()
        val presence = mutableMapOf<Long, Int>()
        val total = mutableMapOf<Long, Int>()
        for (item in attendance) {
            subjectNames.getOrPut(item.subjectId) { item.subjectLongName ?: "" }
            if (!item.isCounted || item.baseType == Attendance.TYPE_UNKNOWN || item.baseType == Attendance.TYPE_DAY_FREE)
                continue
            total[item.subjectId] = (total[item.subjectId] ?: 0) + 1
            if (isPresence(item.baseType, config.belatedAsPresence, config.excusedAsPresence))
                presence[item.subjectId] = (presence[item.subjectId] ?: 0) + 1
        }

        // remove planned skips which are already in the past or have the attendance entered
        val plannedAll = config.plannedSkips.mapNotNull { PlannedSkip.parse(it) }
        val planned = plannedAll.filter { skip ->
            skip.date >= today && attendance.none { it.date == skip.date && it.startTime == skip.startTime }
        }
        if (planned.size != plannedAll.size)
            config.plannedSkips = planned.map { it.serialize() }

        // count the remaining lessons, skipping the free days
        val freeDays = getFreeDays(profile)
        fun isFree(date: Date) = freeDays.any { it.contains(date) }

        // also count the recent lessons which don't have the attendance entered yet
        val pendingFrom = maxOf(today.plusDays(-PENDING_LESSONS_DAYS), semesterStart)
        // match by the lesson time, or by the subject if the e-register doesn't provide the time
        val attendanceKeys = attendance.flatMapTo(mutableSetOf()) {
            listOf("${it.date.value}|${it.startTime?.value}", "${it.date.value}|s${it.subjectId}")
        }
        val lessons = app.db.timetableDao().getBetweenDatesNow(profileId, pendingFrom, semesterEnd)
            .filter { !it.isCancelled && it.type != Lesson.TYPE_NO_LESSONS && it.displaySubjectId != null }
        val remaining = mutableMapOf<Long, Int>()
        for (lesson in lessons) {
            val subjectId = lesson.displaySubjectId ?: continue
            val date = lesson.displayDate ?: continue
            val startTime = lesson.displayStartTime ?: continue
            val isPast = date < today || date == today && startTime <= now
            if (isPast && ("${date.value}|${startTime.value}" in attendanceKeys || "${date.value}|s$subjectId" in attendanceKeys))
                continue
            if (isFree(date))
                continue
            subjectNames.getOrPut(subjectId) { lesson.displaySubjectName ?: "" }
            remaining[subjectId] = (remaining[subjectId] ?: 0) + 1
        }

        // estimate the lessons after the last downloaded day,
        // based on the fullest week of the recently downloaded timetable
        val lastDate = lessons.mapNotNull { it.displayDate }.maxOrNull()
        var remainingEstimated = false
        if (lastDate != null && lastDate < semesterEnd) {
            val typicalWeek = app.db.timetableDao()
                .getBetweenDatesNow(profileId, today.plusDays(-35), lastDate)
                .filter { !it.isCancelled && it.type != Lesson.TYPE_NO_LESSONS && it.displaySubjectId != null && it.displayDate != null }
                .groupBy { it.displayDate!!.weekStart }
                .maxByOrNull { it.value.size }
                ?.value
                .orEmpty()
            // weekDay -> list of subject IDs
            val lessonsByWeekDay = typicalWeek.groupBy({ it.displayDate!!.weekDay }, { it.displaySubjectId!! })
            for (lesson in typicalWeek)
                subjectNames.getOrPut(lesson.displaySubjectId!!) { lesson.displaySubjectName ?: "" }

            var date = lastDate.plusDays(1)
            while (date <= semesterEnd) {
                if (!isFree(date)) {
                    lessonsByWeekDay[date.weekDay]?.forEach { subjectId ->
                        remaining[subjectId] = (remaining[subjectId] ?: 0) + 1
                        remainingEstimated = true
                    }
                }
                date = date.plusDays(1)
            }
        }
        val remainingKnown = lastDate != null

        // index the events
        val ignoredEventTypes = config.ignoredEventTypes.toSet()
        val eventKeys = app.db.eventDao().getAllBetweenDatesNow(profileId, today, semesterEnd)
            .filter { it.type !in ignoredEventTypes }
            .mapTo(mutableSetOf()) {
                val subjectId = if (it.subjectId <= 0) -1 else it.subjectId
                "${it.date.value}|$subjectId|${it.time?.value ?: "*"}"
            }

        val subjectGoals = config.subjectGoals.mapNotNull {
            val (id, goal) = it.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            (id.toLongOrNull() ?: return@mapNotNull null) to (goal.toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        val excluded = config.excludedSubjects.toSet()
        val plannedCount = planned
            .filter { it.date <= semesterEnd }
            .groupingBy { it.subjectId }
            .eachCount()

        val subjects = subjectNames.keys
            .filter { it > 0 || (total[it] ?: 0) > 0 }
            .associateWith { subjectId ->
                val customGoal = subjectGoals[subjectId]
                SubjectStats(
                    subjectId = subjectId,
                    subjectName = subjectNames[subjectId] ?: "",
                    goal = (customGoal ?: config.goalPercent).coerceIn(1, 99),
                    isCustomGoal = customGoal != null,
                    isExcluded = subjectId in excluded,
                    presence = presence[subjectId] ?: 0,
                    total = total[subjectId] ?: 0,
                    planned = plannedCount[subjectId] ?: 0,
                    remaining = remaining[subjectId] ?: 0,
                    remainingEstimated = remainingEstimated,
                    remainingKnown = remainingKnown,
                    marginOk = config.marginOk,
                    marginEdge = config.marginEdge,
                )
            }

        return SkipData(
            profileId = profileId,
            semester = semester,
            semesterStart = semesterStart,
            semesterEnd = semesterEnd,
            subjects = subjects,
            planned = planned.sortedWith(compareBy({ it.date }, { it.startTime })),
            freeDays = freeDays.sortedBy { it.dateFrom },
            eventKeys = eventKeys,
        )
    }

    /*    _____  _                         _
         |  __ \| |                       | |
         | |__) | | __ _ _ __  _ __   ___  __| |
         |  ___/| |/ _` | '_ \| '_ \ / _ \/ _` |
         | |    | | (_| | | | | | | |  __/ (_| |
         |_|    |_|\__,_|_| |_|_| |_|\___|\__,_|*/
    fun isPlanned(lesson: LessonFull, profile: Profile = app.profile): Boolean {
        val skip = PlannedSkip.of(lesson) ?: return false
        return profile.config.skips.plannedSkips.any {
            PlannedSkip.parse(it)?.matches(skip.date, skip.startTime) == true
        }
    }

    fun setPlanned(skips: List<PlannedSkip>, planned: Boolean, profile: Profile = app.profile) {
        val config = profile.config.skips
        val current = config.plannedSkips.mapNotNull { PlannedSkip.parse(it) }
        val result = current.filter { skip ->
            skips.none { it.matches(skip.date, skip.startTime) }
        } + if (planned) skips else listOf()
        config.plannedSkips = result.map { it.serialize() }
        invalidate()
    }

    /**
     * @return whether the lesson is planned to skip after this call
     */
    fun togglePlanned(lesson: LessonFull, profile: Profile = app.profile): Boolean {
        val skip = PlannedSkip.of(lesson) ?: return false
        val planned = !isPlanned(lesson, profile)
        setPlanned(listOf(skip), planned, profile)
        return planned
    }

    fun setSubjectGoal(subjectId: Long, goal: Int?, profile: Profile = app.profile) {
        val config = profile.config.skips
        val goals = config.subjectGoals.filterNot { it.startsWith("$subjectId:") }
        config.subjectGoals = if (goal == null) goals else goals + "$subjectId:$goal"
        invalidate()
    }

    fun setSubjectExcluded(subjectId: Long, excluded: Boolean, profile: Profile = app.profile) {
        val config = profile.config.skips
        val subjects = config.excludedSubjects - subjectId
        config.excludedSubjects = if (excluded) subjects + subjectId else subjects
        invalidate()
    }
}

private fun Date.plusDays(days: Int): Date =
    Date.fromCalendar(asCalendar.apply { add(Calendar.DAY_OF_MONTH, days) })
