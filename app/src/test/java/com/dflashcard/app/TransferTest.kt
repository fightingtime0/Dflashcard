package com.dflashcard.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

class TransferTest {

    // ------------------------------------------------------------------ CSV

    @Test
    fun `csv keeps commas and quotes that live inside a quoted field`() {
        val rows = parseCsv("front,back,extra\n\"a, b\",\"he said \"\"hi\"\"\",note")

        assertEquals(listOf("front", "back", "extra"), rows[0])
        assertEquals(listOf("a, b", "he said \"hi\"", "note"), rows[1])
    }

    @Test
    fun `csv survives a newline inside a quoted field and CRLF line endings`() {
        val rows = parseCsv("a,\"line1\r\nline2\"\r\nb,c\r\n")

        assertEquals(2, rows.size)
        assertEquals("line1\r\nline2", rows[0][1])
        assertEquals(listOf("b", "c"), rows[1])
    }

    @Test
    fun `semicolon exports are detected, since that is what Excel writes in many locales`() {
        val rows = parseCsv("depan;belakang;catatan\nkucing;cat;hewan")

        assertEquals(listOf("kucing", "cat", "hewan"), rows[1])
    }

    @Test
    fun `csv import names the deck after the file and drops the header row`() = runBlocking {
        val dao = FakeDao()

        val result = importCsv(
            dao,
            "front,back,extra\n水,Air,contoh\n猫,Kucing,\n",
            deckName = "kosakata",
        )

        assertEquals(2, result.cards)
        assertEquals("kosakata", dao.decks.single().name)
        assertEquals(listOf("水", "猫"), dao.cards.map { it.front })
    }

    @Test
    fun `csv rows without both sides are skipped`() = runBlocking {
        val dao = FakeDao()

        val result = importCsv(dao, "only-front,\n,only-back\ngood,fine\n", "d")

        assertEquals(1, result.cards)
        assertEquals("good", dao.cards.single().front)
    }

    // ------------------------------------------------------------------ backup

    @Test
    fun `backup round trip preserves scheduling, which is the whole point of a backup`() =
        runBlocking {
            val source = FakeDao()
            source.decks += Deck(id = "deck1", name = "Bahasa Jepang")
            var card = Card(id = "card1", deckId = "deck1", front = "水", back = "Air")
            repeat(3) { card = schedule(card, Rating.GOOD) }
            source.cards += card

            val bytes = ByteArrayOutputStream().also { exportBackup(source, it) }.toByteArray()

            val restored = FakeDao()
            val result = importAnything(restored, bytes.inputStream(), "ignored")

            assertEquals(1, result.decks)
            assertEquals(1, result.cards)
            assertEquals(card, restored.cards.single())
            assertEquals(15, restored.cards.single().intervalDays)
        }

    @Test
    fun `restoring a backup twice changes nothing`() = runBlocking {
        val source = FakeDao()
        source.decks += Deck(id = "deck1", name = "D")
        source.cards += Card(id = "card1", deckId = "deck1", front = "f", back = "b")
        val bytes = ByteArrayOutputStream().also { exportBackup(source, it) }.toByteArray()

        val restored = FakeDao()
        importAnything(restored, bytes.inputStream(), "x")
        importAnything(restored, bytes.inputStream(), "x")

        assertEquals(1, restored.decks.size)
        assertEquals(1, restored.cards.size)
    }

    @Test
    fun `a backup carries tombstones so a restore cannot resurrect deleted rows`() = runBlocking {
        val source = FakeDao()
        source.decks += Deck(id = "deck1", name = "D")
        source.cards += Card(id = "gone", deckId = "deck1", front = "f", back = "b", deletedAt = 42L)
        val bytes = ByteArrayOutputStream().also { exportBackup(source, it) }.toByteArray()

        val restored = FakeDao()
        importAnything(restored, bytes.inputStream(), "x")

        assertEquals(42L, restored.cards.single().deletedAt)
    }

    // ------------------------------------------------------------------ format sniffing

    @Test
    fun `the importer tells the three formats apart on its own`() = runBlocking {
        val legacy = FakeDao()
        importAnything(
            legacy,
            """{"decks": {"D": [{"front": "a", "back": "b", "level": 0}]}}""".byteInputStream(),
            "ignored",
        )
        assertEquals("D", legacy.decks.single().name)

        val csv = FakeDao()
        importAnything(csv, "a,b\n".byteInputStream(), "from-file-name")
        assertEquals("from-file-name", csv.decks.single().name)
    }

    // ------------------------------------------------------------------ speech routing

    @Test
    fun `voice is chosen from the script, and left unset when it says nothing`() {
        assertEquals(Locale.JAPANESE, localeFor("水（みず）"))
        assertEquals(Locale.JAPANESE, localeFor("猫"))
        assertEquals(Locale.KOREAN, localeFor("안녕하세요"))
        assertNull("Latin text must not be guessed at", localeFor("Guten Morgen"))
        assertNull(localeFor(""))
    }

    @Test
    fun `japanese entries are spoken as their reading, not the kanji plus its gloss`() {
        assertEquals("みず", speakableText("水（みず）"))
        assertEquals("안녕하세요", speakableText("안녕하세요"))
        assertTrue(speakableText("友だち（ともだち）") == "ともだち")
    }
}
