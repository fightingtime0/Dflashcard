package com.dflashcard.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- legacy desktop export

/** Shape of the desktop app's flashcards_data.json: {"decks": {"<name>": [ {...}, ... ]}}. */
@Serializable
private data class LegacyFile(val decks: Map<String, List<LegacyCard>> = emptyMap())

@Serializable
private data class LegacyCard(
    val id: String? = null,
    val front: String = "",
    val back: String = "",
    val extra: String = "",
    val level: Int = 0,
)

// ---------------------------------------------------------------- this app's own backup

/**
 * A backup carries every column, tombstones included. Exporting to the legacy shape instead would
 * quietly throw away review history, which makes for a backup that loses exactly what matters.
 */
@Serializable
data class Backup(
    val schemaVersion: Int = BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val decks: List<Deck> = emptyList(),
    val cards: List<Card> = emptyList(),
)

const val BACKUP_VERSION = 2

data class ImportResult(val decks: Int, val cards: Int)

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * The desktop app had no review dates, only level 0/1/2. Seed a plausible first due date so an
 * imported deck does not dump every card into today's queue.
 */
private fun scheduleFor(level: Int, now: Long): Triple<Long, Int, Int> = when (level) {
    1 -> Triple(now + TimeUnit.DAYS.toMillis(1), 1, 1)
    2 -> Triple(now + TimeUnit.DAYS.toMillis(3), 3, 1)
    else -> Triple(now, 0, 0) // never studied, or failed last time
}

/**
 * Reads whatever the user picked. The three supported shapes are distinguishable from their first
 * character and one key, so the caller does not have to ask which format a file is.
 *
 * [fallbackDeckName] names the deck for CSV, which carries no deck of its own.
 */
suspend fun importAnything(
    dao: FlashcardDao,
    input: InputStream,
    fallbackDeckName: String,
): ImportResult {
    val text = input.bufferedReader().use { it.readText() }
    return when {
        !text.trimStart().startsWith("{") -> importCsv(dao, text, fallbackDeckName)
        text.contains("\"schemaVersion\"") -> restoreBackup(dao, text)
        else -> importLegacyJson(dao, text)
    }
}

suspend fun importLegacyJson(dao: FlashcardDao, text: String): ImportResult {
    val parsed = json.decodeFromString<LegacyFile>(text)
    val now = System.currentTimeMillis()
    var importedCards = 0
    var touchedDecks = 0

    for ((rawName, legacyCards) in parsed.decks) {
        val name = rawName.trim()
        if (name.isEmpty()) continue

        val deck = dao.deckByName(name) ?: Deck(name = name).also { dao.upsertDeck(it) }
        touchedDecks++

        val cards = legacyCards.mapNotNull { legacy ->
            val front = legacy.front.trim()
            val back = legacy.back.trim()
            if (front.isEmpty() || back.isEmpty()) return@mapNotNull null

            val (dueAt, interval, reps) = scheduleFor(legacy.level, now)
            Card(
                id = legacy.id ?: UUID.randomUUID().toString(),
                deckId = deck.id,
                front = front,
                back = back,
                extra = legacy.extra.trim(),
                dueAt = dueAt,
                intervalDays = interval,
                reps = reps,
            )
        }
        importedCards += dao.insertNewCards(cards).count { it != -1L }
    }
    return ImportResult(decks = touchedDecks, cards = importedCards)
}

/** Restores a backup in place. Rows already present keep whichever copy was written last. */
suspend fun restoreBackup(dao: FlashcardDao, text: String): ImportResult {
    val backup = json.decodeFromString<Backup>(text)

    for (deck in backup.decks) {
        val existing = dao.deck(deck.id)
        if (existing == null || deck.updatedAt >= existing.updatedAt) dao.upsertDeck(deck)
    }

    // Cards for decks that vanished would be unreachable, and the foreign key would reject them.
    val knownDecks = backup.decks.map { it.id }.toSet()
    val restorable = backup.cards.filter { it.deckId in knownDecks }
    dao.upsertCards(restorable)

    return ImportResult(decks = backup.decks.size, cards = restorable.size)
}

suspend fun exportBackup(dao: FlashcardDao, output: OutputStream) {
    val backup = Backup(
        exportedAt = System.currentTimeMillis(),
        decks = dao.allDecks(),
        cards = dao.allCards(),
    )
    output.bufferedWriter().use { it.write(json.encodeToString(backup)) }
}

// ---------------------------------------------------------------- CSV

suspend fun importCsv(dao: FlashcardDao, text: String, deckName: String): ImportResult {
    val rows = parseCsv(text).filter { row -> row.any { it.isNotBlank() } }
    if (rows.isEmpty()) return ImportResult(0, 0)

    val name = deckName.trim().ifEmpty { "Import" }
    val deck = dao.deckByName(name) ?: Deck(name = name).also { dao.upsertDeck(it) }
    val now = System.currentTimeMillis()

    val body = if (isHeaderRow(rows.first())) rows.drop(1) else rows
    val cards = body.mapNotNull { row ->
        val front = row.getOrElse(0) { "" }.trim()
        val back = row.getOrElse(1) { "" }.trim()
        if (front.isEmpty() || back.isEmpty()) return@mapNotNull null
        Card(
            deckId = deck.id,
            front = front,
            back = back,
            extra = row.getOrElse(2) { "" }.trim(),
            dueAt = now,
        )
    }
    return ImportResult(decks = 1, cards = dao.insertNewCards(cards).count { it != -1L })
}

/** Same heuristic the desktop app used, so a spreadsheet that worked there still works here. */
private fun isHeaderRow(row: List<String>): Boolean {
    val first = row.firstOrNull()?.trim()?.lowercase() ?: return false
    return "front" in first || "depan" in first || "pertanyaan" in first
}

/**
 * RFC 4180 with one concession to reality: spreadsheets in locales that use the comma as a decimal
 * separator export semicolons instead, so the delimiter is sniffed from the first line.
 */
fun parseCsv(text: String): List<List<String>> {
    val delimiter = sniffDelimiter(text)
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0

    fun endField() {
        row.add(field.toString())
        field.setLength(0)
    }

    fun endRow() {
        endField()
        rows.add(row)
        row = mutableListOf()
    }

    while (i < text.length) {
        val ch = text[i]
        when {
            quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                field.append('"')
                i++ // consume the escaped pair
            }

            ch == '"' -> quoted = !quoted
            !quoted && ch == delimiter -> endField()
            !quoted && ch == '\n' -> endRow()
            !quoted && ch == '\r' -> Unit // CRLF: the \n does the work
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}

private fun sniffDelimiter(text: String): Char {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
    return listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
}
