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
    val transfer = state.transactions.find { it.id == parent?.parent_transaction_id }
    val originalAccount = state.accounts.find { it.id == parent?.entries?.singleOrNull()?.account_id }
    val currency = originalAccount?.currency
    val accounts = state.accounts.filter { it.archived_at == null && it.deleted_at == null && it.currency == currency }
    val account = accounts.find { it.id == accountId }
    LaunchedEffect(state.financialCommand?.commandId, state.accounts, state.transactions) {
        val command = state.financialCommand ?: return@LaunchedEffect
        if (command.method != "POST" || !command.path.endsWith("/transactions")) return@LaunchedEffect
        if (ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content != "refund") return@LaunchedEffect
        val draft = decodeResponse<RefundCreate>(command.body)
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
    Text("Новый возврат", style = MaterialTheme.typography.titleLarge)
    state.transactions.filter { it.kind == "expense" && it.status == "posted" }.forEach { expense ->
        val linked = state.transactions.find { it.id == expense.parent_transaction_id }
        TextButton(enabled = enabled && (expense.parent_transaction_id == null || linked?.kind == "transfer"), onClick = {
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
            Text(if (original == null || currency == null) "Обновите данные исходной части." else "Доступно для части возврата ${index + 1}: ${Money.display(original.remaining_refundable_minor, currency)}")
            OutlinedTextField(part.amount, { value -> parts = parts.map { if (it.id == part.id) it.copy(amount = value) else it } }, label = { Text("Сумма части возврата ${index + 1}") }, enabled = enabled, singleLine = true)
        }
        val total = try { if (currency == null) null else refundInputs(currency, parts, parent?.allocations?.associate { it.id to it.remaining_refundable_minor } ?: emptyMap()).first } catch (_: Exception) { null }
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
                val (minor, allocations) = refundInputs(selected.currency, parts, parent.allocations.associate { it.id to it.remaining_refundable_minor })
                val draft = RefundCreate(UUID.randomUUID().toString(), openingInstant(occurred, zone), zone, note, payee, tags, "refund", selected.id, minor, parent.id, parentVersion, transferVersion, allocations.map { it.copy(id = UUID.randomUUID().toString()) })
                error = ""; model.submitTransaction(ApiClient.json.encodeToString(draft))
            } catch (_: Exception) { error = "Проверьте положительные суммы частей, доступный возврат, счет, дату и длину текста." }
        }) { Text("Сохранить возврат") }
        TextButton(enabled = enabled, onClick = { parentId = null; parts = emptyList(); note = ""; error = "" }) { Text("Отменить создание возврата") }
    }
}
