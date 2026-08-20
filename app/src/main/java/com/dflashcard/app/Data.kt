package com.dflashcard.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Rows carry [updatedAt] and a [deletedAt] tombstone rather than being hard-deleted, so a future
 * sync can resolve the same id seen on two devices by last-write-wins instead of guessing.
 */
@Serializable
@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = Deck::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("deckId"), Index("dueAt")],
)
@Serializable
data class Card(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val deckId: String,
    val front: String,
    val back: String,
    val extra: String = "",
    // SM-2 scheduling state. dueAt == 0 means "never studied", which sorts ahead of everything.
    val dueAt: Long = 0L,
    val intervalDays: Int = 0,
    val ease: Double = 2.5,
    val reps: Int = 0,
    val lapses: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** Deck plus the two counts the list screen shows. Not a table. */
data class DeckSummary(
    val id: String,
    val name: String,
    val total: Int,
    val due: Int,
)

@Dao
interface FlashcardDao {

    @Query(
        """
        SELECT d.id AS id, d.name AS name,
               COUNT(c.id) AS total,
               COALESCE(SUM(CASE WHEN c.dueAt <= :dueBefore THEN 1 ELSE 0 END), 0) AS due
        FROM decks d
        LEFT JOIN cards c ON c.deckId = d.id AND c.deletedAt IS NULL
        WHERE d.deletedAt IS NULL
        GROUP BY d.id, d.name
        ORDER BY d.name COLLATE NOCASE
        """
    )
    fun deckSummaries(dueBefore: Long): Flow<List<DeckSummary>>

    @Query("SELECT * FROM cards WHERE deckId = :deckId AND deletedAt IS NULL ORDER BY createdAt")
    fun cardsIn(deckId: String): Flow<List<Card>>

    /** A snapshot, not a Flow: the study queue must stay put while the session is being worked. */
    @Query(
        """
        SELECT * FROM cards
        WHERE deckId = :deckId AND deletedAt IS NULL AND dueAt <= :dueBefore
        ORDER BY dueAt
        LIMIT :limit
        """
    )
    suspend fun dueCards(deckId: String, dueBefore: Long, limit: Int): List<Card>

    @Query("SELECT COUNT(*) FROM decks")
    suspend fun deckCount(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE deletedAt IS NULL AND dueAt <= :dueBefore")
    suspend fun dueCount(dueBefore: Long): Int

    /** Backups include tombstones, so a restore cannot resurrect something already deleted. */
    @Query("SELECT * FROM decks")
    suspend fun allDecks(): List<Deck>

    @Query("SELECT * FROM cards")
    suspend fun allCards(): List<Card>

    @Query("SELECT * FROM decks WHERE deletedAt IS NULL AND name = :name LIMIT 1")
    suspend fun deckByName(name: String): Deck?

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun deck(id: String): Deck?

    @Upsert
    suspend fun upsertDeck(deck: Deck)

    @Upsert
    suspend fun upsertCards(cards: List<Card>)

    @Upsert
    suspend fun upsertCard(card: Card)

    /** Import must never clobber scheduling progress, so an id already present is left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewCards(cards: List<Card>): List<Long>

    @Query("UPDATE cards SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDeleteCard(id: String, now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun softDeleteDeck(id: String, now: Long = System.currentTimeMillis()) {
        markDeckDeleted(id, now)
        markDeckCardsDeleted(id, now)
    }

    @Query("UPDATE decks SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun markDeckDeleted(id: String, now: Long)

    @Query("UPDATE cards SET deletedAt = :now, updatedAt = :now WHERE deckId = :id AND deletedAt IS NULL")
    suspend fun markDeckCardsDeleted(id: String, now: Long)
}

@Database(entities = [Deck::class, Card::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FlashcardDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "dflashcard.db",
            ).build().also { instance = it }
        }
    }
}
