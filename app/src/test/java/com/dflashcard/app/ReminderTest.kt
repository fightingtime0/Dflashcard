package com.dflashcard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The scheduling arithmetic, which is where a daily reminder actually goes wrong: firing twice,
 * firing in the past, or silently landing a day late.
 */
class ReminderTest {

    private fun at(hour: Int, minute: Int, day: Int = 18): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `a time later today is scheduled for today`() {
        val delay = Reminder.millisUntil(hour = 19, minute = 0, now = at(16, 15))

        assertEquals(TimeUnit.MINUTES.toMillis(165), delay)
    }

    @Test
    fun `a time already past today rolls to tomorrow instead of firing immediately`() {
        val delay = Reminder.millisUntil(hour = 9, minute = 0, now = at(16, 15))

        assertEquals(TimeUnit.HOURS.toMillis(16) + TimeUnit.MINUTES.toMillis(45), delay)
        assertTrue("must never schedule into the past", delay > 0)
    }

    @Test
    fun `the exact reminder minute counts as past, so it cannot double fire`() {
        val delay = Reminder.millisUntil(hour = 19, minute = 0, now = at(19, 0))

        assertEquals(TimeUnit.DAYS.toMillis(1), delay)
    }

    @Test
    fun `a delay is always positive and never more than a day out`() {
        for (hour in 0..23) {
            for (minute in listOf(0, 30, 59)) {
                val delay = Reminder.millisUntil(hour, minute, now = at(16, 15))
                assertTrue("$hour:$minute gave $delay", delay > 0)
                assertTrue("$hour:$minute gave $delay", delay <= TimeUnit.DAYS.toMillis(1))
            }
        }
    }

    @Test
    fun `end of today is the last instant of the day, so cards due tonight still count`() {
        val end = Reminder.endOfToday(now = at(16, 15))

        val calendar = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(23, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, calendar.get(Calendar.MINUTE))
        assertTrue(end > at(16, 15))
        assertTrue("must not spill into tomorrow", end < at(0, 0, day = 19))
    }
}
