/*
 * Copyright (c) KayFen 2026-10-29.
 */

package pl.szczodrzynski.edziennik.core.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.szczodrzynski.edziennik.core.manager.SkipManager.Status
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Time

class SkipManagerTest {

    private fun stats(
        presence: Int,
        total: Int,
        planned: Int = 0,
        remaining: Int = 100,
        goal: Int = 51,
        excluded: Boolean = false,
        remainingKnown: Boolean = true,
    ) = SkipManager.SubjectStats(
        subjectId = 1L,
        subjectName = "matematyka",
        goal = goal,
        isCustomGoal = false,
        isExcluded = excluded,
        presence = presence,
        total = total,
        planned = planned,
        remaining = remaining,
        remainingEstimated = false,
        remainingKnown = remainingKnown,
        marginOk = 10,
        marginEdge = 4,
    )

    @Test
    fun percent() {
        val s = stats(presence = 20, total = 30, planned = 2)
        assertEquals(66.67f, s.percent!!, 0.01f)
        assertEquals(62.5f, s.percentPlanned!!, 0.01f)
        assertEquals(60.61f, s.percentAfterSkip()!!, 0.01f)
        assertEquals(62.5f, s.percentAfterSkip(alreadyPlanned = true)!!, 0.01f)
        assertNull(stats(presence = 0, total = 0).percent)
    }

    @Test
    fun canSkip() {
        // 20/39 = 51.3% is fine, 20/40 = 50% is not
        assertEquals(9, stats(presence = 20, total = 30).canSkip)
        // planned skips use up the margin
        assertEquals(6, stats(presence = 20, total = 30, planned = 3).canSkip)
        // already below the goal
        assertTrue(stats(presence = 10, total = 25).canSkip < 0)
    }

    @Test
    fun mustAttend() {
        // 15/30 = 50%, 16/31 = 51.6%
        assertEquals(1, stats(presence = 15, total = 30, remaining = 0, remainingKnown = false).mustAttend)
        // 10/25 = 40% -> 16/31 = 51.6%, 15/30 = 50%
        assertEquals(6, stats(presence = 10, total = 25).mustAttend)
        assertEquals(0, stats(presence = 20, total = 30).mustAttend)
    }

    @Test
    fun statusThresholds() {
        // after skipping: 20/31 = 64.5% -> margin 13.5
        assertEquals(Status.OK, stats(presence = 20, total = 30).getStatus())
        // 18/31 = 58.1% -> margin 7.1
        assertEquals(Status.UNWANTED, stats(presence = 18, total = 30).getStatus())
        // 17/31 = 54.8% -> margin 3.8
        assertEquals(Status.EDGE, stats(presence = 17, total = 30).getStatus())
        // 15/30 = 50%
        assertEquals(Status.FORBIDDEN, stats(presence = 15, total = 29).getStatus())
    }

    @Test
    fun statusWithPlannedSkips() {
        val s = stats(presence = 16, total = 30, planned = 1)
        // the lesson itself is planned: 16/31 = 51.6%
        assertEquals(Status.EDGE, s.getStatus(alreadyPlanned = true))
        // one more skip on top of the planned one: 16/32 = 50%
        assertEquals(Status.FORBIDDEN, s.getStatus(alreadyPlanned = false))
    }

    @Test
    fun statusWithEvent() {
        assertEquals(Status.UNWANTED, stats(presence = 20, total = 30).getStatus(hasEvent = true))
        assertEquals(Status.UNWANTED, stats(presence = 28, total = 30, remaining = 20).getStatus(hasEvent = true))
        // worse statuses are not improved by the event
        assertEquals(Status.EDGE, stats(presence = 17, total = 30).getStatus(hasEvent = true))
        assertEquals(Status.FORBIDDEN, stats(presence = 15, total = 29).getStatus(hasEvent = true))
    }

    @Test
    fun chill() {
        // 28/50 = 56% even when skipping all of the remaining 20 lessons
        val s = stats(presence = 28, total = 30, remaining = 20)
        assertTrue(s.isChill)
        assertEquals(Status.CHILL, s.getStatus())
        assertFalse(stats(presence = 28, total = 30, remaining = 30).isChill)
    }

    @Test
    fun doomed() {
        // 15/40 = 37.5% even when attending all of the remaining 10 lessons
        val s = stats(presence = 5, total = 30, remaining = 10)
        assertTrue(s.isDoomed)
        assertEquals(Status.DOOMED, s.getStatus())
        assertEquals(Status.DOOMED, s.getStatus(hasEvent = true))
        // enough lessons left to fix it
        assertFalse(stats(presence = 5, total = 30, remaining = 60).isDoomed)
    }

    @Test
    fun unknownRemaining() {
        // without the timetable, neither "chill" nor "doomed" can be determined
        assertFalse(stats(presence = 30, total = 30, remaining = 0, remainingKnown = false).isChill)
        assertFalse(stats(presence = 0, total = 30, remaining = 0, remainingKnown = false).isDoomed)
        assertEquals(Status.FORBIDDEN, stats(presence = 0, total = 30, remaining = 0, remainingKnown = false).getStatus())
    }

    @Test
    fun noStatus() {
        assertNull(stats(presence = 0, total = 0).getStatus())
        assertNull(stats(presence = 20, total = 30, excluded = true).getStatus())
    }

    @Test
    fun customGoal() {
        // 20/31 = 64.5% -> margin 4.5 for a 60% goal
        assertEquals(Status.UNWANTED, stats(presence = 20, total = 30, goal = 60).getStatus())
        // 20/31 = 64.5% < 65%
        assertEquals(Status.FORBIDDEN, stats(presence = 20, total = 30, goal = 65).getStatus())
    }

    @Test
    fun easterDate() {
        assertEquals(Date(2019, 4, 21), SkipManager.getEasterDate(2019))
        assertEquals(Date(2024, 3, 31), SkipManager.getEasterDate(2024))
        assertEquals(Date(2025, 4, 20), SkipManager.getEasterDate(2025))
        assertEquals(Date(2026, 4, 5), SkipManager.getEasterDate(2026))
        assertEquals(Date(2027, 3, 28), SkipManager.getEasterDate(2027))
    }

    @Test
    fun plannedSkipSerialization() {
        val skip = SkipManager.PlannedSkip(Date(2026, 11, 6), Time(8, 0, 0), 123L)
        val parsed = SkipManager.PlannedSkip.parse(skip.serialize())
        assertEquals(skip, parsed)
        assertTrue(parsed!!.matches(Date(2026, 11, 6), Time(8, 0, 0)))
        assertFalse(parsed.matches(Date(2026, 11, 6), Time(8, 55, 0)))
        assertNull(SkipManager.PlannedSkip.parse("garbage"))
        assertNull(SkipManager.PlannedSkip.parse("2026-11-06|8:00|123"))
    }

    @Test
    fun freeDaySerialization() {
        val freeDay = SkipManager.FreeDay(
            Date(2027, 1, 18), Date(2027, 1, 31),
            "ferie | zimowe",
            SkipManager.FreeDaySource.MANUAL,
        )
        assertEquals(freeDay, SkipManager.FreeDay.parse(freeDay.serialize()))
        assertTrue(freeDay.contains(Date(2027, 1, 18)))
        assertTrue(freeDay.contains(Date(2027, 1, 25)))
        assertTrue(freeDay.contains(Date(2027, 1, 31)))
        assertFalse(freeDay.contains(Date(2027, 2, 1)))
        assertNull(SkipManager.FreeDay.parse("2027-01-18"))
        assertNull(SkipManager.FreeDay.parse("abc|def|ferie"))
        // no name
        val unnamed = SkipManager.FreeDay(Date(2027, 1, 18), Date(2027, 1, 18), null, SkipManager.FreeDaySource.MANUAL)
        assertEquals(unnamed, SkipManager.FreeDay.parse(unnamed.serialize()))
    }
}
