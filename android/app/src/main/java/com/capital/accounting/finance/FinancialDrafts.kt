package com.capital.accounting.finance

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import com.capital.accounting.api.ApiClient
import com.capital.accounting.api.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Serializable
private data class DraftFile(val schema: Int = 1, val scope: String, val fields: Map<String, String>)

private fun validateDraftFields(fields: Map<String, String>) {
    fields.forEach { (key, value) ->
        when (key) {
            "account.editingId", "catalog.id", "catalog.parentId", "transaction.editingId", "transaction.accountId", "transfer.editingId", "transfer.expectedFeeVersion", "transfer.feeAnchorId", "transfer.sourceCurrency", "transfer.targetCurrency", "transfer.feeCurrency", "transfer.preservedInstant", "transfer.preservedZone", "transfer.sourceId", "transfer.targetId", "transfer.feeAccountId", "refund.editingId", "refund.preservedInstant", "refund.preservedZone", "refund.parentId", "refund.transferVersion", "refund.accountId", "adjustment.accountId", "adjustment.editingId" -> ApiClient.json.decodeFromString<String?>(value)
            "account.editingVersion", "account.editingBalance", "account.name", "account.type", "account.currency", "account.balance", "account.opened", "account.zone", "catalog.collection", "catalog.version", "catalog.name", "transaction.editingVersion", "transaction.kind", "transaction.amount", "transaction.note", "transaction.payee", "transaction.occurred", "transaction.zone", "transfer.editingVersion", "transfer.feeDraftId", "transfer.sourceAmount", "transfer.targetAmount", "transfer.note", "transfer.payee", "transfer.occurred", "transfer.zone", "transfer.feeAmount", "transfer.feeNote", "refund.editingVersion", "refund.parentVersion", "refund.note", "refund.payee", "refund.occurred", "refund.zone", "adjustment.balanceVersion", "adjustment.target", "adjustment.reason", "adjustment.note", "adjustment.zone", "adjustment.editingVersion" -> ApiClient.json.decodeFromString<String>(value)
            "account.archived", "catalog.archived", "transfer.feeEnabled" -> ApiClient.json.decodeFromString<Boolean>(value)
            "transaction.original" -> ApiClient.json.decodeFromString<Transaction?>(value)
            "transaction.parts", "transfer.feeParts" -> ApiClient.json.decodeFromString<List<AllocationDraft>>(value)
            "transaction.selectedTags", "transfer.tags", "transfer.feeTags", "refund.tags" -> ApiClient.json.decodeFromString<List<String>>(value)
            "refund.parts" -> ApiClient.json.decodeFromString<List<RefundDraft>>(value)
            else -> error("Unknown draft field")
        }
    }
}

/** Unsent form values only. Credentials and submitted commands have separate stores. */
class FinancialDrafts(context: Context) {
    private val directory = File(context.noBackupFilesDir, "financial-drafts")
    private val executor = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val scopes = ConcurrentHashMap<String, FinancialDraftScope>()

    suspend fun open(origin: String, owner: String, workspace: String): FinancialDraftScope = withContext(Dispatchers.IO) {
        val identity = ApiClient.json.encodeToString(listOf(origin, owner, workspace))
        val scope = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        scopes[scope] ?: run {
            check(directory.isDirectory || directory.mkdirs())
            val file = AtomicFile(File(directory, "$scope.json"))
            val fields = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val bytes = file.openRead().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= 1_048_576)
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toByteArray()
                }
                val saved = ApiClient.json.decodeFromString<DraftFile>(bytes.decodeToString())
                require(saved.schema == 1 && saved.scope == scope && saved.fields.size <= 256)
                validateDraftFields(saved.fields)
                saved.fields
            } else emptyMap()
            val loaded = FinancialDraftScope(fields) { snapshot, complete ->
                executor.execute {
                    var stream: java.io.FileOutputStream? = null
                    try {
                        require(snapshot.size <= 256)
                        validateDraftFields(snapshot)
                        val bytes = ApiClient.json.encodeToString(DraftFile(scope = scope, fields = snapshot)).toByteArray()
                        require(bytes.size <= 1_048_576)
                        stream = file.startWrite()
                        stream.write(bytes)
                        file.finishWrite(stream)
                        // AtomicFile can log a failed rename without throwing. Verify the committed bytes.
                        require(file.openRead().use { it.readBytes() }.contentEquals(bytes))
                        main.post { complete(true) }
                    } catch (_: Exception) {
                        runCatching { file.failWrite(stream) }
                        main.post { complete(false) }
                    }
                }
            }
            scopes.putIfAbsent(scope, loaded) ?: loaded
        }
    }
}

class FinancialDraftScope internal constructor(
    initial: Map<String, String>,
    private val save: (Map<String, String>, (Boolean) -> Unit) -> Unit,
) {
    private val fields = initial.toMutableMap()
    private var submitted = initial
    private var writing by mutableStateOf(false)
    private var scheduled by mutableStateOf(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    val saving get() = writing || scheduled
    var failed by mutableStateOf(false)
        private set
    @Volatile private var revision = 0L

    fun read(key: String): String? = fields[key]
    fun put(key: String, value: String) {
        if (fields.put(key, value) == value || scheduled) return
        scheduled = true
        // One main-loop task sees the complete event/composition, even when only a child recomposes.
        main.post {
            scheduled = false
            persist()
        }
    }

    fun persist(retry: Boolean = false) {
        val snapshot = fields.toMap()
        if (snapshot == submitted && !retry) return
        submitted = snapshot
        val current = ++revision
        writing = true
        save(snapshot) { success ->
            // Older writes must not acknowledge a newer, still uncommitted snapshot.
            if (current == revision) { writing = false; failed = !success }
        }
    }
}

val LocalFinancialDraftScope = staticCompositionLocalOf<FinancialDraftScope> { error("Authenticated draft scope required") }

@Composable
inline fun <reified T> rememberFinancialDraft(key: String, noinline initial: () -> T): MutableState<T> {
    val scope = LocalFinancialDraftScope.current
    val value = remember(scope, key) {
        val stored = scope.read(key)
        val state = mutableStateOf(if (stored == null) initial() else ApiClient.json.decodeFromString<T>(stored))
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) { state.value = value; scope.put(key, ApiClient.json.encodeToString(value)) }
            override fun component1(): T = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
    // Commit a complete composition snapshot, including generated allocation IDs and times.
    SideEffect { scope.put(key, ApiClient.json.encodeToString(value.value)) }
    return value
}

@Composable
fun FinancialDraftContent(context: Context, origin: String, owner: String, workspace: String, content: @Composable () -> Unit) {
    val application = context.applicationContext as com.capital.accounting.CapitalApplication
    var scope by remember(origin, owner, workspace) { mutableStateOf<FinancialDraftScope?>(null) }
    var failed by remember(origin, owner, workspace) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(origin, owner, workspace, attempt) {
        failed = false
        try { scope = application.financialDrafts.open(origin, owner, workspace) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    val loaded = scope
    if (loaded != null) {
        CompositionLocalProvider(LocalFinancialDraftScope provides loaded) {
            content()
            if (loaded.failed) androidx.compose.material3.Text("Не удалось сохранить черновики на устройстве. Повторите сохранение.")
            if (loaded.failed) androidx.compose.material3.Button(onClick = { loaded.persist(retry = true) }) { androidx.compose.material3.Text("Повторить сохранение черновиков") }
            androidx.compose.material3.Text(if (loaded.saving) "Сохранение черновиков на устройстве…" else if (!loaded.failed) "Локальные черновики сохранены. Отправка операций выполняется отдельно." else "Черновики остаются в памяти.")
        }
    } else if (failed) {
        androidx.compose.material3.Text("Не удалось прочитать черновики. Исходный файл сохранен.")
        androidx.compose.material3.Button(onClick = { attempt++ }) { androidx.compose.material3.Text("Повторить чтение черновиков") }
    } else androidx.compose.material3.CircularProgressIndicator()
}
