package com.dflashcard.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One pass through a deck's due cards. [current] is null once the queue is worked through. */
data class Session(
    val current: Card? = null,
    val remaining: Int = 0,
    val reviewed: Int = 0,
    val lapsed: Int = 0,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class Vm(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.get(app).dao()

    // Counting against an instant would be wrong: Room binds the parameter once, so a card saved a
    // moment later would never satisfy `dueAt <= now` until the next launch. Anything due by the end
    // of today counts as due today, which also matches how a learner thinks about a daily queue.
    // ponytail: the boundary is bound at launch, so an app left open past midnight shows yesterday's
    // cut-off until it is next foregrounded. Recompute on resume if that ever bites.
    val decks = dao.deckSummaries(endOfToday())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openDeckId = MutableStateFlow<String?>(null)

    val cards = openDeckId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.cardsIn(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val reviewQueue = mutableListOf<Card>()
    private val _session = MutableStateFlow(Session())
    val session = _session.asStateFlow()

    init {
        viewModelScope.launch {
            if (dao.deckCount() == 0) {
                runCatching {
                    app.assets.open(SEED_ASSET).use { importAnything(dao, it, "Import") }
                }
            }
        }
    }

    fun openDeck(id: String?) {
        openDeckId.value = id
    }

    fun addDeck(name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isNotEmpty() && dao.deckByName(clean) == null) dao.upsertDeck(Deck(name = clean))
    }

    fun renameDeck(id: String, name: String) = viewModelScope.launch {
        val clean = name.trim()
        val deck = dao.deck(id) ?: return@launch
        if (clean.isNotEmpty()) {
            dao.upsertDeck(deck.copy(name = clean, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteDeck(id: String) = viewModelScope.launch {
        if (openDeckId.value == id) openDeckId.value = null
        dao.softDeleteDeck(id)
    }

    fun saveCard(deckId: String, existing: Card?, front: String, back: String, extra: String) =
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val card = existing?.copy(
                front = front.trim(),
                back = back.trim(),
                extra = extra.trim(),
                updatedAt = now,
            ) ?: Card(
                deckId = deckId,
                front = front.trim(),
                back = back.trim(),
                extra = extra.trim(),
                dueAt = now,
            )
            dao.upsertCard(card)
        }

    fun deleteCard(id: String) = viewModelScope.launch { dao.softDeleteCard(id) }

    fun startSession(deckId: String) = viewModelScope.launch {
        _session.value = Session(loading = true)
        reviewQueue.clear()
        reviewQueue += dao.dueCards(deckId, endOfToday(), SESSION_LIMIT)
        emitSession(reviewed = 0, lapsed = 0)
    }

    fun rate(rating: Rating) = viewModelScope.launch {
        if (reviewQueue.isEmpty()) return@launch
        val snapshot = _session.value
        val reviewed = schedule(reviewQueue.removeAt(0), rating)
        dao.upsertCard(reviewed)

        // A failed card goes back into the queue a few places down rather than reappearing at once,
        // so the learner has to recall it rather than parrot the answer still on screen.
        if (rating == Rating.AGAIN) {
            reviewQueue.add(minOf(reviewQueue.size, RELEARN_GAP), reviewed)
        }
        emitSession(
            reviewed = snapshot.reviewed + 1,
            lapsed = snapshot.lapsed + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    private fun emitSession(reviewed: Int, lapsed: Int) {
        _session.value = Session(
            current = reviewQueue.firstOrNull(),
            remaining = reviewQueue.size,
            reviewed = reviewed,
            lapsed = lapsed,
            loading = false,
        )
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val result = runCatching {
            val stream = app.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
            stream.use { importAnything(dao, it, fallbackDeckName = displayName(uri)) }
        }
        _message.value = result.fold(
            onSuccess = { app.getString(R.string.import_done, it.cards, it.decks) },
            onFailure = { app.getString(R.string.import_failed, it.message ?: it.javaClass.simpleName) },
        )
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val result = runCatching {
            val stream = app.contentResolver.openOutputStream(uri) ?: error("cannot write $uri")
            stream.use { exportBackup(dao, it) }
        }
        _message.value = result.fold(
            onSuccess = { app.getString(R.string.export_done) },
            onFailure = { app.getString(R.string.export_failed, it.message ?: it.javaClass.simpleName) },
        )
    }

    /** CSV carries no deck name, so the file's own name becomes the deck. */
    private fun displayName(uri: Uri): String {
        val app = getApplication<Application>()
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0).substringBeforeLast('.')
            }
        }
        return "Import"
    }

    fun messageShown() {
        _message.value = null
    }

    private companion object {
        const val SEED_ASSET = "seed.json"

        /** A ceiling on one sitting, so a freshly imported deck is not a wall of 500 cards. */
        const val SESSION_LIMIT = 60
        const val RELEARN_GAP = 4

        /** Local end-of-day, so "due" means "due at some point today". */
        fun endOfToday(): Long = Reminder.endOfToday()
    }
}
