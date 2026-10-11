package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.ApiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID

@Serializable
data class SavedFinancialDraft(val id: String, val prefix: String, val fields: Map<String, String>)
@Serializable
data class DraftManagement(val baselines: Map<String, Map<String, String>> = emptyMap(), val saved: List<SavedFinancialDraft> = emptyList())

internal fun validateDraftManagement(value: DraftManagement) {
    val prefixes = setOf("account", "catalog", "transaction", "transfer", "refund", "adjustment")
    require(value.saved.size <= 20 && value.saved.map { it.id }.distinct().size == value.saved.size)
    fun validate(prefix: String, fields: Map<String, String>) {
        require(prefix in prefixes && fields.size <= 256 && fields.keys.all { it.startsWith("$prefix.") })
        validateDraftFields(fields)
    }
    value.baselines.forEach { (prefix, fields) -> validate(prefix, fields) }
    value.saved.forEach { require(UUID.fromString(it.id).toString() == it.id); validate(it.prefix, it.fields) }
}

private fun FinancialDraftScope.management() = read("draft.management")?.let { ApiClient.json.decodeFromString<DraftManagement>(it) } ?: DraftManagement()
private fun FinancialDraftScope.management(value: DraftManagement) {
    validateDraftManagement(value)
    put("draft.management", ApiClient.json.encodeToString(value))
}

class DraftGuard internal constructor(private val scope: FinancialDraftScope, private val prefix: String) {
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set
    var error by mutableStateOf("")
        private set
    val dirty get() = scope.snapshot(prefix) != scope.management().baselines[prefix].orEmpty()
    val saved get() = scope.management().saved.filter { it.prefix == prefix }
    fun markClean() {
        val value = scope.management()
        scope.management(value.copy(baselines = value.baselines + (prefix to scope.snapshot(prefix))))
    }
    fun initialize() {
        if (prefix in scope.management().baselines) return
        if (scope.initiallyPresent.any { it.startsWith("$prefix.") }) {
            val value = scope.management()
            scope.management(value.copy(baselines = value.baselines + (prefix to emptyMap())))
        } else markClean()
    }
    fun request(action: () -> Unit) {
        error = ""
        if (dirty) pending = action else { action(); markClean() }
    }
    fun stay() { pending = null; error = "" }
    fun discard() {
        val action = pending ?: return
        pending = null
        action()
        markClean()
    }
    fun keep() {
        val action = pending ?: return
        val value = scope.management()
        if (value.saved.size >= 20) { error = "Сохранено 20 черновиков. Отбросьте один перед сохранением нового."; return }
        scope.management(value.copy(saved = value.saved + SavedFinancialDraft(UUID.randomUUID().toString(), prefix, scope.snapshot(prefix))))
        pending = null
        action()
        markClean()
    }
    fun remove(id: String) {
        val value = scope.management()
        scope.management(value.copy(saved = value.saved.filterNot { it.id == id }))
    }
    fun restore(id: String) = request {
        val value = scope.management()
        val item = value.saved.single { it.id == id && it.prefix == prefix }
        scope.replace(prefix, item.fields)
        scope.management(value)
    }
}

@Composable
fun rememberDraftGuard(prefix: String, enabled: Boolean): DraftGuard {
    val scope = LocalFinancialDraftScope.current
    val noun = when (prefix) { "transfer" -> "перевода"; "refund" -> "возврата"; "adjustment" -> "корректировки"; else -> "операции" }
    val guard = remember(scope, prefix) { DraftGuard(scope, prefix) }
    SideEffect {
        android.os.Handler(android.os.Looper.getMainLooper()).post { guard.initialize() }
    }
    if (guard.dirty || guard.saved.any { it.fields == scope.snapshot(prefix) }) Text("Несохраненный черновик $noun")
    guard.saved.forEachIndexed { index, item ->
        TextButton(enabled = enabled && !scope.saving && !scope.failed, onClick = { guard.restore(item.id) }) { Text("Восстановить черновик $noun ${index + 1}") }
        TextButton(enabled = enabled && !scope.saving, onClick = { guard.remove(item.id) }) { Text("Отбросить сохраненный черновик $noun ${index + 1}") }
    }
    if (guard.pending != null) AlertDialog(
        onDismissRequest = guard::stay,
        title = { Text("Сохранить измененный черновик?") },
        text = { Text(if (guard.error.isEmpty()) "Черновик можно сохранить на устройстве и вернуть позже." else guard.error) },
        confirmButton = { TextButton(enabled = enabled && !scope.saving && !scope.failed, onClick = guard::keep) { Text("Сохранить черновик и продолжить") } },
        dismissButton = {
            TextButton(enabled = enabled && !scope.saving, onClick = guard::discard) { Text("Отбросить черновик и продолжить") }
            TextButton(onClick = guard::stay) { Text("Остаться в редакторе") }
        },
    )
    return guard
}
