package app.cardkeep.ingest

import android.content.Context
import app.cardkeep.CardsApp
import app.cardkeep.data.Card
import app.cardkeep.data.ImportLog
import app.cardkeep.data.Ledger
import app.cardkeep.domain.Issuers
import app.cardkeep.reminders.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline PDF statement import pipeline: PDF bytes -> unlock & extract text -> parse -> encrypted DB.
 * Runs entirely on-device with no network access.
 */
object StatementImporter {
    private val mutex = Mutex()

    private class LocalPdf(
        val key: String,
        val fileName: String,
        val receivedAt: Long,
        val bytes: ByteArray,
    )

    /** Result of fixing or importing one PDF: the outcome code and a sentence for the UI. */
    data class Outcome(val code: String, val message: String, val statementId: Long? = null, val key: String = "")

    /** Retries a kept PDF with a typed password and/or a chosen card. */
    suspend fun fix(context: Context, key: String, password: String?, cardId: Long?, remember: Boolean): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = CardsApp.from(context)
            val ledger = app.repo.ledger
            val e = ledger.logEntry(key) ?: return@withContext Outcome("error", "That statement isn't in the list any more.")
            val pdf = ledger.pendingPdf(key) ?: return@withContext Outcome("error", "The PDF isn't stored any more. Import the file again.")
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards()
            val local = LocalPdf(e.key, pdf.fileName, e.receivedAt, pdf.bytes)
            val pw = password?.trim()?.takeIf { it.isNotEmpty() }
            val r = processOne(context, ledger, cards, ledger.profile(ledger.mailSourceOnce()), local, listOfNotNull(pw), cards.firstOrNull { it.id == cardId })
            if (pw != null && remember && r.code != "locked") ledger.rememberPassword(pw)
            r
        }
    }

    /** Imports a PDF picked from Files or shared from another app on this device. */
    suspend fun importFile(context: Context, fileName: String, bytes: ByteArray): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = CardsApp.from(context)
            val ledger = app.repo.ledger
            Pdf.init(context)
            ledger.ensureSeeded()
            val cards = app.repo.activeCards()
            if (cards.isEmpty()) return@withContext Outcome("error", "Add a card first.")
            val local = LocalPdf("file:" + Pdf.sha256(bytes), fileName, System.currentTimeMillis(), bytes)
            processOne(context, ledger, cards, ledger.profile(ledger.mailSourceOnce()), local)
        }
    }

    private suspend fun processOne(
        context: Context,
        ledger: Ledger,
        cards: List<Card>,
        profile: PdfProfile,
        pdf: LocalPdf,
        extraPasswords: List<String> = emptyList(),
        forceCard: Card? = null,
    ): Outcome {
        suspend fun done(outcome: String, detail: String, message: String, statementId: Long? = null, keep: Boolean = false): Outcome {
            ledger.log(ImportLog(pdf.key, "", pdf.fileName, "", pdf.receivedAt, outcome, detail, statementId))
            if (keep) ledger.keepPdf(pdf.key, pdf.fileName, pdf.bytes) else ledger.dropPdf(pdf.key)
            return Outcome(outcome, message, statementId, pdf.key)
        }

        val hash = Pdf.sha256(pdf.bytes)
        if (ledger.hashSeen(hash)) return done("duplicate", "Already imported", "Already imported.")

        val issuer = forceCard?.let { Issuers.byName(it.issuer) }
        val issuerCards = forceCard?.let { listOf(it) } ?: cards.filter { issuer != null && it.issuer == issuer.name }.ifEmpty { cards }
        val candidates = extraPasswords + Passwords.candidates(profile, issuerCards.map { it.last4 }.distinct())

        val text = when (val r = Pdf.extract(pdf.bytes, candidates)) {
            is Pdf.Result.Text -> r.text
            Pdf.Result.Locked -> return done(
                "locked",
                if (extraPasswords.isEmpty()) "Password-protected. Tap to enter the password." else "That password didn't work.",
                if (extraPasswords.isEmpty()) "It's password-protected." else "That password didn't work.",
                keep = true,
            )
            is Pdf.Result.Failed -> return done("error", r.reason, "Couldn't open the PDF.")
        }
        if (text.isBlank()) return done("unparsed", "No text in the PDF (scanned image?)", "There's no text in this PDF.")

        val first = Parsers.forIssuer(issuer?.name ?: "")
        val firstParsed = first.parse(text, null)
        val card = forceCard ?: matchCard(issuerCards, cards, firstParsed.last4, text)
            ?: return done(
                "unmatched",
                "Couldn't tell which ${issuer?.name?.plus(" ") ?: ""}card this is for (last 4 ${firstParsed.last4 ?: "not found"}). Tap to pick it.",
                "Couldn't tell which card this is for.",
                keep = true,
            )
        val parser = Parsers.forIssuer(card.issuer)
        val parsed = if (parser.name == first.name) firstParsed else parser.parse(text, null)

        val saved = ledger.importStatement(
            card, parsed, java.time.Instant.ofEpochMilli(pdf.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay(),
            "", hash, parser.name, text,
        )
        val ok = saved.status == Ledger.STATUS_OK
        Notifications.showStatement(context, card, saved)
        return done(
            if (ok) "imported" else "needs_review",
            if (ok) "${parsed.lines.size} lines" else saved.note,
            if (ok) "Imported to ${card.nickname}." else "Imported to ${card.nickname}, but it needs a look.",
            saved.id,
        )
    }

    private fun matchCard(cards: List<Card>, allCards: List<Card>, last4: String?, text: String): Card? {
        if (last4 != null) return cards.firstOrNull { it.last4 == last4 } ?: allCards.singleOrNull { it.last4 == last4 }
        val mentioned = cards.filter { c -> c.last4.isNotBlank() && Regex("(?<!\\d)${Regex.escape(c.last4)}(?!\\d)").containsMatchIn(text) }
        if (mentioned.size == 1) return mentioned[0]
        return cards.singleOrNull()
    }
}
