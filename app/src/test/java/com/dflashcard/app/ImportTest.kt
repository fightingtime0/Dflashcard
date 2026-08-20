package com.dflashcard.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Covers the migration path off the desktop app, against the real exported file. */
class ImportTest {

    private val seed = File("src/main/assets/seed.json")

    private suspend fun importSeed(dao: FakeDao) =
        seed.inputStream().use { importAnything(dao, it, "seed") }

    @Test
    fun `imports the desktop export and is idempotent on a second run`() = runBlocking {
        val dao = FakeDao()

        val first = importSeed(dao)
        assertEquals(4, first.decks)
        assertEquals(101, first.cards)
        assertEquals(101, dao.cards.size)

        // Re-importing the same file must not duplicate anything or reset scheduling.
        val second = importSeed(dao)
        assertEquals(0, second.cards)
        assertEquals(101, dao.cards.size)
        assertEquals(4, dao.decks.size)
    }

    @Test
    fun `deck names are trimmed so near-duplicates merge`() = runBlocking {
        val dao = FakeDao()
        val json = """
            {"decks": {
              "Bahasa Jepang ": [{"id": "a", "front": "水", "back": "Air", "level": 0}],
              "Bahasa Jepang":  [{"id": "b", "front": "猫", "back": "Kucing", "level": 0}]
            }}
        """.trimIndent()

        val result = importAnything(dao, json.byteInputStream(), "ignored")

        assertEquals(1, dao.decks.size)
        assertEquals("Bahasa Jepang", dao.decks.single().name)
        assertEquals(2, result.cards)
        assertTrue(dao.cards.all { it.deckId == dao.decks.single().id })
    }

    @Test
    fun `level maps onto a first due date instead of dumping everything into today`() = runBlocking {
        val dao = FakeDao()
        val before = System.currentTimeMillis()
        val json = """
            {"decks": {"D": [
              {"id": "new",  "front": "a", "back": "1", "level": 0},
              {"id": "ok",   "front": "b", "back": "2", "level": 1},
              {"id": "easy", "front": "c", "back": "3", "level": 2}
            ]}}
        """.trimIndent()

        importAnything(dao, json.byteInputStream(), "ignored")
        val byId = dao.cards.associateBy { it.id }

        assertTrue(byId.getValue("new").dueAt <= System.currentTimeMillis())
        assertEquals(0, byId.getValue("new").reps)
        assertDueInAbout(1, byId.getValue("ok").dueAt, before)
        assertDueInAbout(3, byId.getValue("easy").dueAt, before)
        assertEquals(3, byId.getValue("easy").intervalDays)
    }

    @Test
    fun `rows missing a front or back are skipped rather than imported blank`() = runBlocking {
        val dao = FakeDao()
        val json = """
            {"decks": {"D": [
              {"front": "ok",  "back": "yes"},
              {"front": "   ", "back": "no"},
              {"front": "no",  "back": ""}
            ]}}
        """.trimIndent()

        val result = importAnything(dao, json.byteInputStream(), "ignored")

        assertEquals(1, result.cards)
        assertEquals("ok", dao.cards.single().front)
    }

    private fun assertDueInAbout(days: Long, dueAt: Long, importedAt: Long) {
        val expected = importedAt + TimeUnit.DAYS.toMillis(days)
        assertTrue(
            "expected due ~$days day(s) out, got ${dueAt - importedAt}ms",
            dueAt in expected..(expected + TimeUnit.MINUTES.toMillis(1)),
        )
    }
}
