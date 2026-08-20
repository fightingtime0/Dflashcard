package com.dflashcard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SchedulerTest {

    private val now = 1_700_000_000_000L
    private fun newCard() = Card(deckId = "d", front = "f", back = "b")

    private fun daysOut(card: Card) =
        TimeUnit.MILLISECONDS.toDays(card.dueAt - now).toInt()

    @Test
    fun `a new card climbs the ladder 1, 6, then by its ease`() {
        var card = newCard()

        card = schedule(card, Rating.GOOD, now)
        assertEquals(1, card.intervalDays)
        assertEquals(1, card.reps)

        card = schedule(card, Rating.GOOD, now)
        assertEquals(6, card.intervalDays)

        // Third pass multiplies by ease (2.5), not by a fixed step.
        card = schedule(card, Rating.GOOD, now)
        assertEquals(15, card.intervalDays)
        assertEquals(15, daysOut(card))
    }

    @Test
    fun `again resets the ladder, counts a lapse and returns the card within the hour`() {
        var card = newCard()
        repeat(3) { card = schedule(card, Rating.GOOD, now) }
        assertTrue(card.intervalDays > 1)

        card = schedule(card, Rating.AGAIN, now)

        assertEquals(0, card.intervalDays)
        assertEquals(0, card.reps)
        assertEquals(1, card.lapses)
        assertTrue("failed card must come back this session", card.dueAt - now < TimeUnit.HOURS.toMillis(1))

        // And it has to earn the long intervals again from the bottom.
        card = schedule(card, Rating.GOOD, now)
        assertEquals(1, card.intervalDays)
    }

    @Test
    fun `ratings order intervals from shortest to longest`() {
        var card = newCard()
        repeat(3) { card = schedule(card, Rating.GOOD, now) }

        val again = nextIntervalDays(card, Rating.AGAIN)
        val hard = nextIntervalDays(card, Rating.HARD)
        val good = nextIntervalDays(card, Rating.GOOD)
        val easy = nextIntervalDays(card, Rating.EASY)

        assertTrue("$again < $hard < $good < $easy", again < hard && hard < good && good < easy)
    }

    @Test
    fun `ease drifts with difficulty but never below the floor`() {
        var card = newCard()
        card = schedule(card, Rating.EASY, now)
        assertTrue("easy should raise ease", card.ease > 2.5)

        // Repeated failures must not drive ease toward zero and trap the card in daily reviews.
        repeat(20) { card = schedule(card, Rating.AGAIN, now) }
        assertEquals(1.3, card.ease, 0.0001)
    }

    @Test
    fun `intervals stay within a sane ceiling`() {
        var card = newCard()
        repeat(40) { card = schedule(card, Rating.EASY, now) }
        assertTrue("interval ran away: ${card.intervalDays}", card.intervalDays <= 365 * 5)
    }

    @Test
    fun `rating stamps updatedAt so a future sync can resolve the row`() {
        val card = schedule(newCard(), Rating.GOOD, now)
        assertEquals(now, card.updatedAt)
    }
}
