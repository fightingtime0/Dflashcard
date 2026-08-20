package com.dflashcard.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory stand-in for Room, so import, export and scheduling run without a device. */
internal class FakeDao : FlashcardDao {

    val decks = mutableListOf<Deck>()
    val cards = mutableListOf<Card>()

    override fun deckSummaries(dueBefore: Long): Flow<List<DeckSummary>> = flowOf(
        decks.filter { it.deletedAt == null }.map { deck ->
            val live = cards.filter { it.deckId == deck.id && it.deletedAt == null }
            DeckSummary(
                id = deck.id,
                name = deck.name,
                total = live.size,
                due = live.count { it.dueAt <= dueBefore },
            )
        }
    )

    override fun cardsIn(deckId: String): Flow<List<Card>> =
        flowOf(cards.filter { it.deckId == deckId && it.deletedAt == null })

    override suspend fun dueCards(deckId: String, dueBefore: Long, limit: Int): List<Card> =
        cards.filter { it.deckId == deckId && it.deletedAt == null && it.dueAt <= dueBefore }
            .sortedBy { it.dueAt }
            .take(limit)

    override suspend fun deckCount() = decks.size

    override suspend fun dueCount(dueBefore: Long) =
        cards.count { it.deletedAt == null && it.dueAt <= dueBefore }

    override suspend fun allDecks(): List<Deck> = decks.toList()

    override suspend fun allCards(): List<Card> = cards.toList()

    override suspend fun deckByName(name: String) =
        decks.firstOrNull { it.deletedAt == null && it.name == name }

    override suspend fun deck(id: String) = decks.firstOrNull { it.id == id }

    override suspend fun upsertDeck(deck: Deck) {
        decks.removeAll { it.id == deck.id }
        decks += deck
    }

    override suspend fun upsertCard(card: Card) {
        cards.removeAll { it.id == card.id }
        cards += card
    }

    override suspend fun upsertCards(cards: List<Card>) = cards.forEach { upsertCard(it) }

    override suspend fun insertNewCards(cards: List<Card>): List<Long> = cards.map { candidate ->
        if (this.cards.any { it.id == candidate.id }) -1L else {
            this.cards += candidate
            1L
        }
    }

    override suspend fun softDeleteCard(id: String, now: Long) {
        cards.replaceAll { if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it }
    }

    override suspend fun markDeckDeleted(id: String, now: Long) {
        decks.replaceAll { if (it.id == id) it.copy(deletedAt = now, updatedAt = now) else it }
    }

    override suspend fun markDeckCardsDeleted(id: String, now: Long) {
        cards.replaceAll {
            if (it.deckId == id && it.deletedAt == null) {
                it.copy(deletedAt = now, updatedAt = now)
            } else {
                it
            }
        }
    }
}
