package app.cardkeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.cardkeep.data.Card as CardEntity
import app.cardkeep.data.ImportLog
import app.cardkeep.data.Ledger
import app.cardkeep.domain.Dates
import app.cardkeep.ingest.StatementImporter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline PDF statement imports: pick local PDFs, resolve locked/unmatched statements,
 * manage statement unlock passwords, and review recent imports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementImportsScreen(
    ledger: Ledger,
    log: List<ImportLog>,
    cards: List<CardEntity>,
    needsYou: List<ImportLog>,
    fixKey: String?,
    onFixKeyConsumed: () -> Unit,
    onImport: () -> Unit,
    onImported: (StatementImporter.Outcome) -> Unit,
    onOpenStatement: (Long) -> Unit,
    onPasswords: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fixing by remember { mutableStateOf<ImportLog?>(null) }

    // Opened from an import that needs a password or a card: go straight to that fix.
    LaunchedEffect(fixKey, log) {
        val k = fixKey ?: return@LaunchedEffect
        log.firstOrNull { it.key == k }?.let { fixing = it; onFixKeyConsumed() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF statements") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("imports_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel {
                Column {
                    Text("Import from this phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick a statement PDF from Files or share one to CardVault from another app. PDFs are unlocked and read completely offline on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onImport,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("import_pdf_button"),
                    ) {
                        Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                        Text("  Import statement PDF")
                    }
                }
            }

            NeedsYouSection(needsYou) { fixing = it }

            SectionHeader("Settings")
            Panel(padded = false) {
                ListRow(
                    Icons.Outlined.Key,
                    "Statement passwords",
                    "Name, birth date, PAN, used to unlock PDFs locally",
                    chevron = true,
                    onClick = onPasswords,
                )
            }

            RecentImports(log, onOpenStatement)
        }
    }

    val locked = LocalLocked.current
    fixing?.takeIf { !locked }?.let { e ->
        ImportFixDialog(
            entry = e,
            cards = cards,
            onDismiss = { fixing = null },
            onSkip = { fixing = null; scope.launch { ledger.dismiss(e.key) } },
            onResult = { r -> fixing = null; onImported(r) },
        )
    }
}

/** Imports by month; duplicates and skipped ones folded away. */
@Composable
private fun RecentImports(log: List<ImportLog>, onOpenStatement: (Long) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val quiet = setOf("duplicate", "dismissed")
    val relevant = (if (showAll) log else log.filter { it.outcome !in quiet && it.outcome !in FIXABLE }).take(60)
    val shown = if (expanded) relevant else relevant.take(RECENT_PREVIEW)
    val hidden = log.count { it.outcome in quiet }
    if (log.isEmpty()) return

    SectionHeader("Recent imports")
    if (shown.isEmpty()) {
        Text("Nothing imported yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Panel(padded = false) {
            Column {
                val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
                shown.groupBy { YearMonth.from(Instant.ofEpochMilli(it.receivedAt).atZone(ZoneId.systemDefault())) }.forEach { (ym, list) ->
                    Text(
                        ym.format(fmt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    )
                    list.forEachIndexed { i, e ->
                        val sid = e.statementId
                        if (i > 0) RowDivider(inset = false)
                        ListRow(
                            icon = null,
                            title = e.subject.ifBlank { "Statement PDF" },
                            subtitle = Dates.short(Instant.ofEpochMilli(e.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()) + " · " + e.detail,
                            onClick = sid?.let { { onOpenStatement(it) } },
                            action = {
                                Text(
                                    outcomeLabel(e.outcome),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (e.outcome) {
                                        "imported" -> Good
                                        "needs_review" -> Amber
                                        "duplicate", "dismissed" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> Danger
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    Row {
        if (relevant.size > RECENT_PREVIEW) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${relevant.size}") }
        }
        if (hidden > 0 && (expanded || relevant.size <= RECENT_PREVIEW)) {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Hide duplicates and skipped" else "Show $hidden duplicates and skipped") }
        }
    }
}

private const val RECENT_PREVIEW = 6

private fun outcomeLabel(o: String) = when (o) {
    "imported" -> "Imported"
    "needs_review" -> "Review"
    "duplicate" -> "Duplicate"
    "dismissed" -> "Skipped"
    "locked" -> "Locked"
    "unmatched" -> "No card"
    "unparsed" -> "Unreadable"
    else -> "Error"
}
