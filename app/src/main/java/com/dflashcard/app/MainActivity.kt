package com.dflashcard.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DFlashcardTheme { App() }
        }
    }
}

@Composable
private fun DFlashcardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(vm: Vm = viewModel()) {
    val decks by vm.decks.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The open deck is held by id and re-derived from the list, so a rename retitles the screen and
    // a delete drops us back to the list without any extra bookkeeping.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    val openDeck = decks.firstOrNull { it.id == openId }
    val deckId = openId

    // Leaving the browser rebuilds the queue, since cards may have been added or edited in there.
    LaunchedEffect(deckId, browsing) {
        vm.openDeck(deckId)
        if (deckId != null && !browsing) vm.startSession(deckId)
    }
    LaunchedEffect(decks, openId) {
        if (openId != null && openDeck == null) {
            openId = null
            browsing = false
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when {
            openDeck == null -> DeckListScreen(
                decks = decks,
                padding = padding,
                onStudy = {
                    openId = it.id
                    browsing = false
                },
                onBrowse = {
                    openId = it.id
                    browsing = true
                },
                onCreate = { vm.addDeck(it) },
                onRename = { id, name -> vm.renameDeck(id, name) },
                onDelete = { vm.deleteDeck(it) },
                onImport = { vm.importFrom(it) },
                onExport = { vm.exportTo(it) },
            )

            browsing -> {
                val cards by vm.cards.collectAsStateWithLifecycle()
                BackHandler { browsing = false }
                CardListScreen(
                    deck = openDeck,
                    cards = cards,
                    padding = padding,
                    onBack = { browsing = false },
                    onSave = { existing, front, back, extra ->
                        vm.saveCard(openDeck.id, existing, front, back, extra)
                    },
                    onDelete = { vm.deleteCard(it) },
                )
            }

            else -> {
                val session by vm.session.collectAsStateWithLifecycle()
                BackHandler { openId = null }
                StudyScreen(
                    deck = openDeck,
                    session = session,
                    padding = padding,
                    onBack = { openId = null },
                    onBrowse = { browsing = true },
                    onRate = { vm.rate(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckListScreen(
    decks: List<DeckSummary>,
    padding: PaddingValues,
    onStudy: (DeckSummary) -> Unit,
    onBrowse: (DeckSummary) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (Uri) -> Unit,
    onExport: (Uri) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var reminding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DeckSummary?>(null) }
    var deleting by remember { mutableStateOf<DeckSummary?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExport) }

    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.decks)) },
            actions = {
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_file)) },
                            onClick = {
                                overflow = false
                                picker.launch(IMPORT_MIME_TYPES)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_backup)) },
                            onClick = {
                                overflow = false
                                saver.launch("dflashcard-backup.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.daily_reminder)) },
                            onClick = {
                                overflow = false
                                reminding = true
                            },
                        )
                    }
                }
            },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (decks.isEmpty()) {
                EmptyHint(stringResource(R.string.no_decks))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(decks, key = { it.id }) { deck ->
                        DeckRow(
                            deck = deck,
                            onClick = { onStudy(deck) },
                            onBrowse = { onBrowse(deck) },
                            onRename = { renaming = deck },
                            onDelete = { deleting = deck },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            ) { Icon(Icons.Default.Add, stringResource(R.string.new_deck)) }
        }
    }

    if (reminding) {
        ReminderDialog(onDismiss = { reminding = false })
    }
    if (creating) {
        TextPromptDialog(
            title = stringResource(R.string.new_deck),
            label = stringResource(R.string.deck_name),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = {
                onCreate(it)
                creating = false
            },
        )
    }
    renaming?.let { deck ->
        TextPromptDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.deck_name),
            initial = deck.name,
            onDismiss = { renaming = null },
            onConfirm = {
                onRename(deck.id, it)
                renaming = null
            },
        )
    }
    deleting?.let { deck ->
        ConfirmDialog(
            title = stringResource(R.string.delete_deck_title),
            body = stringResource(R.string.delete_deck_body, deck.name),
            onDismiss = { deleting = null },
            onConfirm = {
                onDelete(deck.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun DeckRow(
    deck: DeckSummary,
    onClick: () -> Unit,
    onBrowse: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleMedium)
                val counts = buildString {
                    append(pluralStringResource(R.plurals.card_count, deck.total, deck.total))
                    if (deck.due > 0) {
                        append("  ·  ").append(stringResource(R.string.due_count, deck.due))
                    }
                }
                Text(
                    counts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.cards_action)) },
                        onClick = {
                            menu = false
                            onBrowse()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardListScreen(
    deck: DeckSummary,
    cards: List<Card>,
    padding: PaddingValues,
    onBack: () -> Unit,
    onSave: (Card?, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<Card?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Card?>(null) }

    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
    ) {
        TopAppBar(
            title = { Text(deck.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (cards.isEmpty()) {
                EmptyHint(stringResource(R.string.no_cards))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.id }) { card ->
                        CardRow(card, onClick = { editing = card }, onDelete = { deleting = card })
                    }
                }
            }
            FloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            ) { Icon(Icons.Default.Add, stringResource(R.string.new_card)) }
        }
    }

    if (adding || editing != null) {
        CardEditorDialog(
            card = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onConfirm = { front, back, extra ->
                onSave(editing, front, back, extra)
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { card ->
        ConfirmDialog(
            title = stringResource(R.string.delete_card_title),
            body = stringResource(R.string.delete_card_body),
            onDismiss = { deleting = null },
            onConfirm = {
                onDelete(card.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun CardRow(card: Card, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.front, style = MaterialTheme.typography.titleMedium)
                Text(card.back, style = MaterialTheme.typography.bodyMedium)
                if (card.extra.isNotBlank()) {
                    Text(
                        card.extra,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun CardEditorDialog(
    card: Card?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var front by rememberSaveable(card?.id) { mutableStateOf(card?.front.orEmpty()) }
    var back by rememberSaveable(card?.id) { mutableStateOf(card?.back.orEmpty()) }
    var extra by rememberSaveable(card?.id) { mutableStateOf(card?.extra.orEmpty()) }
    val valid = front.isNotBlank() && back.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (card == null) R.string.new_card else R.string.edit_card)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text(stringResource(R.string.front)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text(stringResource(R.string.back_field)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = it },
                    label = { Text(stringResource(R.string.extra)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(front, back, extra) }, enabled = valid) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudyScreen(
    deck: DeckSummary,
    session: Session,
    padding: PaddingValues,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onRate: (Rating) -> Unit,
) {
    val card = session.current
    // Keyed on the card, so the next one always arrives face down.
    var showBack by remember(card?.id) { mutableStateOf(false) }
    val speaker = rememberSpeaker()

    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
    ) {
        TopAppBar(
            title = { Text(deck.name) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = {
                // Only offered when the script tells us which voice to use.
                if (card != null && localeFor(card.front) != null) {
                    IconButton(onClick = { speaker.speak(card.front) }) {
                        Icon(
                            painterResource(R.drawable.ic_speaker),
                            stringResource(R.string.speak),
                        )
                    }
                }
                TextButton(onClick = onBrowse) { Text(stringResource(R.string.cards_action)) }
            },
        )

        when {
            session.loading -> Spacer(Modifier.weight(1f))

            card == null -> SessionSummary(
                session = session,
                onBrowse = onBrowse,
                modifier = Modifier.weight(1f),
            )

            else -> {
                Text(
                    stringResource(R.string.cards_left, session.remaining),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp),
                )
                Flashcard(
                    card = card,
                    showBack = showBack,
                    onFlip = { showBack = !showBack },
                    modifier = Modifier
                        .weight(1f)
                        .padding(24.dp),
                )
                RatingBar(
                    card = card,
                    visible = showBack,
                    onRate = onRate,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun Flashcard(
    card: Card,
    showBack: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (showBack) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "flip",
    )

    Card(
        onClick = onFlip,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation
                // Without a finite camera distance the card visibly warps mid-turn.
                cameraDistance = 12f * density
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (rotation < 90f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        card.front,
                        style = MaterialTheme.typography.displaySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.tap_to_flip),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // The whole card is mirrored at this point, so the back face is flipped back.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { rotationY = 180f },
                ) {
                    Text(
                        card.back,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (card.extra.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            card.extra,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingBar(
    card: Card,
    visible: Boolean,
    onRate: (Rating) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row keeps its space while hidden, so revealing the answer never shifts the card.
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Rating.entries.forEach { rating ->
            val label = when (rating) {
                Rating.AGAIN -> R.string.rating_again
                Rating.HARD -> R.string.rating_hard
                Rating.GOOD -> R.string.rating_good
                Rating.EASY -> R.string.rating_easy
            }
            FilledTonalButton(
                onClick = { onRate(rating) },
                enabled = visible,
                contentPadding = PaddingValues(4.dp),
                colors = if (rating == Rating.AGAIN) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (visible) 1f else 0f),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(
                        intervalLabel(nextIntervalDays(card, rating)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSummary(session: Session, onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                stringResource(
                    if (session.reviewed > 0) R.string.session_complete else R.string.nothing_due
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (session.reviewed > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reviewed_summary, session.reviewed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.lapsed > 0) {
                    Text(
                        stringResource(R.string.lapsed_summary, session.lapsed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onBrowse) { Text(stringResource(R.string.browse_cards)) }
        }
    }
}

/** Compact next-review estimate for a rating button: 10m, 6d, 3mo, 1y. */
@Composable
private fun intervalLabel(days: Int): String = when {
    days <= 0 -> stringResource(R.string.interval_soon)
    days < 30 -> stringResource(R.string.interval_days, days)
    days < 365 -> stringResource(R.string.interval_months, days / 30)
    else -> stringResource(R.string.interval_years, days / 365)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(Reminder.isEnabled(context)) }
    val time = rememberTimePickerState(
        initialHour = Reminder.hour(context),
        initialMinute = Reminder.minute(context),
        is24Hour = true,
    )

    fun apply() {
        if (enabled) Reminder.enable(context, time.hour, time.minute) else Reminder.disable(context)
        onDismiss()
    }

    // On 33+ the reminder is pointless without the runtime grant, so ask at the moment it is turned on.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { apply() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.daily_reminder)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.reminder_enable),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Spacer(Modifier.height(16.dp))
                    TimeInput(state = time)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        apply()
                    }
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Exports carry no registered handler on most devices, so allow a loose pick. */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")
