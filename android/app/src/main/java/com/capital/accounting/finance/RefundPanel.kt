package com.capital.accounting.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RefundPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf("") }
    var preservedInstant by remember { mutableStateOf<String?>(null) }
    var preservedZone by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    var deletingParent by remember { mutableStateOf<Transaction?>(null) }
    var deletingTransfer by remember { mutableStateOf<Transaction?>(null) }
    var parentId by remember { mutableStateOf<String?>(null) }
    var parentVersion by remember { mutableStateOf("") }
    var transferVersion by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var parts by remember { mutableStateOf<List<RefundDraft>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var occurred by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())) }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val parent = state.transactions.find { it.id == parentId }
    val edited = state.transactions.find { it.id == editingId }
    val remaining = try { refundRemaining(parent?.allocations ?: emptyList(), edited?.allocations ?: emptyList()) } catch (_: Exception) { emptyMap() }
    val transfer = state.transactions.find { it.id == parent?.parent_transaction_id }
    val originalAccount = state.accounts.find { it.id == parent?.entries?.singleOrNull()?.account_id }
    val currency = originalAccount?.currency
    val accounts = state.accounts.filter { it.archived_at == null && it.deleted_at == null && it.currency == currency }
    val account = accounts.find { it.id == accountId }
    LaunchedEffect(state.financialCommand?.commandId, state.accounts, state.transactions) {
        val command = state.financialCommand
        if (command == null) {
            if (editingId != null && state.message == "Операция сохранена") {
                if (edited == null) { editingId = null; parentId = null; parts = emptyList() }
                else { editingVersion = edited.version; parentVersion = parent?.version ?: parentVersion; transferVersion = transfer?.version }
            }
            return@LaunchedEffect
        }
        if (command.method !in listOf("POST", "PUT") || !command.path.contains("/transactions")) return@LaunchedEffect
        if (ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content != "refund") return@LaunchedEffect
        val draft = if (command.method == "POST") {
            editingId = null
            decodeResponse<RefundCreate>(command.body)
        } else {
            val replacement = decodeResponse<RefundReplace>(command.body)
            editingId = command.path.substringAfterLast('/'); editingVersion = replacement.expected_version
            RefundCreate(editingId!!, replacement.occurred_at, replacement.occurred_timezone, replacement.note, replacement.payee, replacement.tag_ids, replacement.kind, replacement.account_id, replacement.amount_minor, replacement.parent_transaction_id, replacement.expected_parent_version, replacement.expected_transfer_version, replacement.allocations)
        }
        preservedInstant = draft.occurred_at; preservedZone = draft.occurred_timezone
        parentId = draft.parent_transaction_id; parentVersion = draft.expected_parent_version; transferVersion = draft.expected_transfer_version
        accountId = draft.account_id; note = draft.note; payee = draft.payee; zone = draft.occurred_timezone; occurred = openingLocal(draft.occurred_at, zone); tags = draft.tag_ids
        val accountCurrency = state.accounts.find { it.id == draft.account_id }?.currency ?: return@LaunchedEffect
        val originals = state.transactions.find { it.id == draft.parent_transaction_id }?.allocations ?: emptyList()
        val requested = draft.allocations.associateBy { it.original_allocation_id }
        parts = originals.map { original ->
            val part = requested[original.id]
            if (part == null) RefundDraft(original.id) else RefundDraft(original.id, part.id, Money.display(part.amount_minor, accountCurrency).removeSuffix(" $accountCurrency"))
        } + draft.allocations.filter { requestedPart -> originals.none { it.id == requestedPart.original_allocation_id } }.map { RefundDraft(it.original_allocation_id, it.id, Money.display(it.amount_minor, accountCurrency).removeSuffix(" $accountCurrency")) }
    }
    Text(if (editingId == null) "Новый возврат" else "Изменение возврата", style = MaterialTheme.typography.titleLarge)
    state.transactions.filter { it.kind == "expense" && it.status == "posted" }.forEach { expense ->
        val linked = state.transactions.find { it.id == expense.parent_transaction_id }
        TextButton(enabled = enabled && editingId == null && (expense.parent_transaction_id == null || linked?.kind == "transfer"), onClick = {
            preservedInstant = null; preservedZone = null
            parentId = expense.id; parentVersion = expense.version; transferVersion = linked?.version
            accountId = expense.entries.single().account_id; parts = expense.allocations.map { RefundDraft(it.id) }
            note = ""; payee = expense.payee; tags = emptyList(); error = ""
        }) { Text(if (expense.parent_transaction_id == null) "Создать возврат расхода ${expense.note}" else "Создать возврат комиссии ${expense.note}") }
    }
    if (parentId == null) Text("Выберите исходный расход или комиссию.")
    else {
        Text("Исходный расход: ${parent?.note ?: "Обновите историю"}")
        Text("Категории возврата наследуются от частей исходного расхода.")
        accounts.forEach { item -> TextButton(enabled = enabled, onClick = { accountId = item.id }) { Text("Счет возврата: ${item.name} · ${item.currency}") } }
        Text("Возврат зачисляется: ${account?.name ?: "Выберите активный счет той же валюты"}")
        parts.forEachIndexed { index, part ->
            val original = parent?.allocations?.find { it.id == part.originalId }
            Text("Часть возврата ${index + 1}: ${categoryPath(original?.category_id, state.categories)}")
            Text(if (original == null || currency == null) "Обновите данные исходной части." else "Доступно для части возврата ${index + 1}: ${Money.display(remaining[original.id] ?: "0", currency)}")
            OutlinedTextField(part.amount, { value -> parts = parts.map { if (it.id == part.id) it.copy(amount = value) else it } }, label = { Text("Сумма части возврата ${index + 1}") }, enabled = enabled, singleLine = true)
        }
        val total = try { if (currency == null) null else refundInputs(currency, parts, remaining).first } catch (_: Exception) { null }
        if (total != null && currency != null) Text("Сумма возврата: ${Money.display(total, currency)}")
        OutlinedTextField(occurred, { occurred = it }, label = { Text("Дата возврата (YYYY-MM-DDTHH:MM:SS)") }, enabled = enabled, singleLine = true)
        OutlinedTextField(zone, { zone = it }, label = { Text("Часовой пояс возврата") }, enabled = enabled, singleLine = true)
        OutlinedTextField(payee, { payee = it }, label = { Text("Получатель возврата") }, enabled = enabled, singleLine = true)
        OutlinedTextField(note, { note = it }, label = { Text("Примечание возврата") }, enabled = enabled)
        state.tags.filter { it.archived_at == null || it.id in tags }.forEach { tag ->
            Row {
                Checkbox(tag.id in tags, { checked -> tags = if (checked) (tags + tag.id).distinct() else tags - tag.id }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Тег возврата ${tag.name}" })
                Text("Тег возврата: ${tag.name}")
            }
        }
        if (parent != null && (parent.version != parentVersion || transfer?.version != transferVersion)) {
            Text("Версия расхода на сервере: ${parent.version}; черновика: $parentVersion")
            Text("Версия связанного перевода: ${transfer?.version ?: "нет"}; черновика: ${transferVersion ?: "нет"}")
            Button(enabled = enabled && (parent.parent_transaction_id == null || transfer != null), onClick = { parentVersion = parent.version; transferVersion = transfer?.version }) { Text("Использовать обновленные версии расхода и перевода") }
        }
        if (error.isNotEmpty()) Text(error)
        Button(enabled = enabled && parent != null && originalAccount?.archived_at == null && account != null && (parent.parent_transaction_id == null || transfer != null), onClick = {
            try {
                val selected = requireNotNull(account)
                require(parent?.kind == "expense" && parent.status == "posted" && note.length <= 2000 && payee.length <= 200 && tags.size <= 50)
                val (minor, allocations) = refundInputs(selected.currency, parts, remaining)
                val instant = if (preservedInstant != null && zone == preservedZone && occurred == openingLocal(preservedInstant!!, preservedZone!!)) preservedInstant!! else openingInstant(occurred, zone)
                val request = if (editingId == null) ApiClient.json.encodeToString(RefundCreate(UUID.randomUUID().toString(), instant, zone, note, payee, tags, "refund", selected.id, minor, parent.id, parentVersion, transferVersion, allocations.map { it.copy(id = UUID.randomUUID().toString()) }))
                    else ApiClient.json.encodeToString(RefundReplace(instant, zone, note, payee, tags, "refund", selected.id, minor, parent.id, parentVersion, transferVersion, allocations, editingVersion))
                error = ""; model.submitTransaction(request, editingId)
            } catch (_: Exception) { error = "Проверьте положительные суммы частей, доступный возврат, счет, дату и длину текста." }
        }) { Text(if (editingId == null) "Сохранить возврат" else "Сохранить изменения возврата") }
        if (edited != null && edited.version != editingVersion) {
            Text("Версия возврата на сервере: ${edited.version}; черновика: $editingVersion")
            Button(enabled = enabled, onClick = { editingVersion = edited.version }) { Text("Использовать обновленную версию возврата") }
        }
        state.transactionConflict?.takeIf { it.kind == "refund" }?.let { Text("Возврат на сервере: ${it.note}; версия ${it.version}") }
        TextButton(enabled = enabled, onClick = { editingId = null; parentId = null; parts = emptyList(); note = ""; preservedInstant = null; preservedZone = null; error = "" }) { Text(if (editingId == null) "Отменить создание возврата" else "Отменить изменение возврата") }
    }
    deleting?.let { refund ->
        Text("Удалить возврат: ${refund.note}")
        Text("Зачисление будет исключено из остатка, квота исходного расхода восстановится.")
        Button(enabled = enabled && deletingParent != null && (deletingParent?.parent_transaction_id == null || deletingTransfer != null), onClick = {
            val related = listOfNotNull(deletingParent, deletingTransfer).map { VersionExpectation(it.id, it.version) }
            model.submitTransaction(ApiClient.json.encodeToString(TransactionDelete(refund.version, related)), refund.id, delete = true)
            deleting = null; deletingParent = null; deletingTransfer = null
        }) { Text("Подтвердить удаление возврата") }
        TextButton(enabled = enabled, onClick = { deleting = null; deletingParent = null; deletingTransfer = null }) { Text("Отменить удаление возврата") }
    }
    state.transactions.filter { it.kind == "refund" }.forEach { refund ->
        val original = state.transactions.find { it.id == refund.parent_transaction_id }
        val linked = state.transactions.find { it.id == original?.parent_transaction_id }
        val available = original != null && (original.parent_transaction_id == null || linked != null)
        TextButton(enabled = enabled && available, onClick = {
            editingId = refund.id; editingVersion = refund.version; parentId = original!!.id; parentVersion = original.version; transferVersion = linked?.version
            accountId = refund.entries.single().account_id; note = refund.note; payee = refund.payee; tags = refund.tag_ids; zone = refund.occurred_timezone; occurred = openingLocal(refund.occurred_at, zone)
            preservedInstant = refund.occurred_at; preservedZone = zone
            val byOriginal = refund.allocations.associateBy { it.original_allocation_id }
            parts = original.allocations.map { part ->
                val existing = byOriginal[part.id]
                if (existing == null) RefundDraft(part.id) else RefundDraft(part.id, existing.id, Money.display(existing.amount_minor, refund.entries.single().currency).removeSuffix(" ${refund.entries.single().currency}"))
            }
            error = ""
        }) { Text("Изменить возврат ${refund.note}") }
        TextButton(enabled = enabled && available, onClick = { deleting = refund; deletingParent = original; deletingTransfer = linked }) { Text("Удалить возврат ${refund.note}") }
    }

}
