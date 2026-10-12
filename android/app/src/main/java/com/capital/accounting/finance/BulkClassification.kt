package com.capital.accounting.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigInteger
import java.util.UUID

@Serializable
data class BulkSelection(val id: String, val version: String, val label: String)

internal fun validateBulkSelection(items: List<BulkSelection>) {
    require(items.size <= 100 && items.map { it.id }.distinct().size == items.size)
    items.forEach {
        require(UUID.fromString(it.id).toString() == it.id && it.label.length <= 4096)
        require(BigInteger(it.version) in BigInteger.ONE..BigInteger.valueOf(Long.MAX_VALUE))
    }
}

class BulkClassificationController(
    val model: AuthViewModel,
    val selection: MutableState<List<BulkSelection>>,
    val category: MutableState<String?>,
    val tags: MutableState<List<String>>,
    val scope: FinancialDraftScope,
) {
    val enabled get() = !model.state.busy && model.state.persisted && !model.state.financeBlocked && !scope.saving && !scope.failed
    fun eligible(item: Transaction): Boolean = item.deleted_at == null && item.status == "posted" &&
        item.kind in listOf("income", "expense") && item.parent_transaction_id == null &&
        item.entries.size == 1 && item.allocations.size == 1 &&
        model.state.accounts.any { it.id == item.entries.single().account_id && it.deleted_at == null && it.archived_at == null } &&
        (item.kind != "expense" || item.allocations.single().remaining_refundable_minor == item.allocations.single().amount_minor) &&
        model.state.transactions.none { it.kind == "refund" && it.deleted_at == null && it.parent_transaction_id == item.id }
    fun label(item: Transaction) = item.note.ifEmpty { "${if (item.kind == "income") "Доход" else "Расход"} ${item.entries.joinToString { Money.display(it.amount_minor, it.currency) }} · ${openingLocal(item.occurred_at, item.occurred_timezone)}" }
    fun snapshot(item: Transaction) = BulkSelection(item.id, item.version, label(item))
    fun current(item: BulkSelection) = model.state.transactions.find { it.id == item.id }
    val valid get() = selection.value.isNotEmpty() && selection.value.all { chosen -> current(chosen)?.let { eligible(it) && it.version == chosen.version } == true } &&
        (category.value == null || model.state.categories.any { it.id == category.value && it.archived_at == null && it.deleted_at == null }) &&
        tags.value.all { id -> model.state.tags.any { it.id == id && it.archived_at == null && it.deleted_at == null } }
}

@Composable
fun rememberBulkClassification(model: AuthViewModel): BulkClassificationController {
    val selection = rememberFinancialDraft<List<BulkSelection>>("bulk.selection") { emptyList() }
    val category = rememberFinancialDraft<String?>("bulk.categoryId") { null }
    val tags = rememberFinancialDraft<List<String>>("bulk.tags") { emptyList() }
    val controller = BulkClassificationController(model, selection, category, tags, LocalFinancialDraftScope.current)
    var handledReceipt by remember { mutableStateOf(model.state.classificationReceipt?.commandId) }
    LaunchedEffect(model.state.financialCommand?.commandId, model.state.classificationReceipt?.commandId) {
        val command = model.state.financialCommand
        if (command?.path?.endsWith("/transactions/classification") == true) {
            val body = decodeResponse<BulkClassification>(command.body)
            selection.value = body.items.map { item -> selection.value.find { it.id == item.id }?.copy(version = item.expected_version)
                ?: BulkSelection(item.id, item.expected_version, model.state.transactions.find { it.id == item.id }?.let(controller::label) ?: "Недоступная операция") }
            category.value = body.category_id
            tags.value = body.tag_ids
        }
        val receipt = model.state.classificationReceipt
        if (command == null && receipt != null && receipt.commandId != handledReceipt) {
            handledReceipt = receipt.commandId
            selection.value = selection.value.filterNot { it.id in receipt.ids }
        }
    }
    return controller
}

@Composable
fun BulkClassificationControls(bulk: BulkClassificationController) {
    Text("Массовая классификация", style = MaterialTheme.typography.titleLarge)
    Text("До 100 самостоятельных доходов и расходов с одной частью, без возвратов и на активных счетах. Категория и теги заменяются; суммы не меняются.")
    Text("Выбрано для классификации: ${bulk.selection.value.size}")
    TextButton(enabled = bulk.enabled, onClick = {
        val existing = bulk.selection.value
        bulk.selection.value = (existing + (bulk.model.state.historyItems ?: bulk.model.state.transactions)
            .filter { bulk.eligible(it) && existing.none { chosen -> chosen.id == it.id } }.map(bulk::snapshot)).take(100)
    }) { Text("Выбрать доступные операции на странице") }
    if (bulk.selection.value.isNotEmpty()) {
        bulk.selection.value.forEach { selected ->
            val current = bulk.current(selected)
            if (current == null || !bulk.eligible(current) || current.version != selected.version) {
                Text("${selected.label}: выбранная версия ${selected.version}; ${if (current != null && bulk.eligible(current)) "версия на сервере ${current.version}" else "операция недоступна для классификации"}")
                TextButton(enabled = bulk.enabled, onClick = { bulk.selection.value = bulk.selection.value.filterNot { it.id == selected.id } }) { Text("Снять выбор: ${selected.label}") }
            }
        }
        TextButton(enabled = bulk.enabled, onClick = { bulk.selection.value = bulk.selection.value.map { selected -> bulk.current(selected)?.takeIf(bulk::eligible)?.let(bulk::snapshot) ?: selected } }) { Text("Обновить версии выбранных операций") }
        TextButton(enabled = bulk.enabled, onClick = { bulk.selection.value = emptyList() }) { Text("Снять выбор операций") }
        Text("Категория пакета: ${bulk.model.state.categories.find { it.id == bulk.category.value }?.name ?: "Без категории"}")
        if (bulk.category.value != null && bulk.model.state.categories.none { it.id == bulk.category.value && it.archived_at == null && it.deleted_at == null }) {
            Text("Выбранная категория недоступна. Выберите другую или уберите категорию пакета.")
        }
        TextButton(enabled = bulk.enabled, onClick = { bulk.category.value = null }) { Text("Пакет без категории") }
        bulk.model.state.categories.filter { it.archived_at == null && it.deleted_at == null }.forEach { category ->
            TextButton(enabled = bulk.enabled, onClick = { bulk.category.value = category.id }) { Text("Категория пакета: ${category.name}") }
        }
        if (bulk.tags.value.any { id -> bulk.model.state.tags.none { it.id == id && it.archived_at == null && it.deleted_at == null } }) {
            Text("Некоторые выбранные теги недоступны. Уберите теги пакета и выберите актуальные.")
        }
        TextButton(enabled = bulk.enabled, onClick = { bulk.tags.value = emptyList() }) { Text("Пакет без тегов") }
        bulk.model.state.tags.filter { it.archived_at == null && it.deleted_at == null }.forEach { tag ->
            Row {
                Checkbox(checked = tag.id in bulk.tags.value, enabled = bulk.enabled && (tag.id in bulk.tags.value || bulk.tags.value.size < 50),
                    modifier = Modifier.semantics { contentDescription = "Массовый тег ${tag.name}" },
                    onCheckedChange = { bulk.tags.value = if (it) bulk.tags.value + tag.id else bulk.tags.value - tag.id })
                Text(tag.name)
            }
        }
        Button(enabled = bulk.enabled && bulk.valid, onClick = {
            val body = BulkClassification(bulk.selection.value.map { BulkItem(it.id, it.version) }, bulk.category.value, bulk.tags.value)
            bulk.model.submitClassification(ApiClient.json.encodeToString(body))
        }) { Text("Применить классификацию к выбранным операциям") }
    }
}

@Composable
fun BulkClassificationRow(bulk: BulkClassificationController, item: Transaction) {
    if (!bulk.eligible(item)) return
    val selected = bulk.selection.value.any { it.id == item.id }
    Row {
        Checkbox(checked = selected, enabled = bulk.enabled && (selected || bulk.selection.value.size < 100),
            modifier = Modifier.semantics { contentDescription = "Классифицировать: ${bulk.label(item)}" },
            onCheckedChange = { bulk.selection.value = if (it) bulk.selection.value + bulk.snapshot(item) else bulk.selection.value.filterNot { chosen -> chosen.id == item.id } })
        Text("Выбрать для классификации")
    }
}
