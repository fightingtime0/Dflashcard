package com.dflashcard.app

import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** What the learner said about a card they just saw. */
enum class Rating { AGAIN, HARD, GOOD, EASY }

/** Below this an ease factor stops shrinking, or a hard card would spiral into daily reviews. */
private const val MIN_EASE = 1.3
private const val MAX_INTERVAL_DAYS = 365 * 5

/** A lapsed card comes back within the same sitting instead of waiting for tomorrow. */
private val RELEARN_DELAY_MS = TimeUnit.MINUTES.toMillis(10)

/**
 * SM-2, the algorithm behind Anki. The interval grows by the card's own ease factor, and that factor
 * itself moves with how hard the card felt, so a card you keep failing keeps coming back sooner.
 *
 * ponytail: FSRS schedules measurably better but needs a review-history table and a parameter
 * optimizer. The history is worth adding first; this stays a drop-in replacement when it is.
 */
fun schedule(card: Card, rating: Rating, now: Long = System.currentTimeMillis()): Card {
    val interval = nextIntervalDays(card, rating)
    val ease = when (rating) {
        Rating.AGAIN -> card.ease - 0.20
        Rating.HARD -> card.ease - 0.15
        Rating.GOOD -> card.ease
        Rating.EASY -> card.ease + 0.15
    }.coerceAtLeast(MIN_EASE)

    return card.copy(
        intervalDays = interval,
        ease = ease,
        // A lapse restarts the ladder, so the card has to earn its long intervals again.
        reps = if (rating == Rating.AGAIN) 0 else card.reps + 1,
        lapses = if (rating == Rating.AGAIN) card.lapses + 1 else card.lapses,
        dueAt = if (interval == 0) {
            now + RELEARN_DELAY_MS
        } else {
            now + TimeUnit.DAYS.toMillis(interval.toLong())
        },
        updatedAt = now,
    )
}

/**
 * The interval a rating would produce, in days. Pure, so the review buttons can show the learner
 * what each choice costs before they commit to it.
 */
fun nextIntervalDays(card: Card, rating: Rating): Int = when (rating) {
    Rating.AGAIN -> 0
    Rating.HARD -> if (card.intervalDays == 0) 1 else grow(card.intervalDays, 1.2)
    Rating.GOOD -> when (card.reps) {
        0 -> 1
        1 -> 6
        else -> grow(card.intervalDays, card.ease)
    }

    Rating.EASY -> when (card.reps) {
        0 -> 4
        1 -> 10
        else -> grow(card.intervalDays, card.ease * 1.3)
    }
}

private fun grow(days: Int, factor: Double): Int =
    ceil(days * factor).toInt().coerceIn(1, MAX_INTERVAL_DAYS)
