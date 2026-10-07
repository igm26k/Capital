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
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TransactionPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<Transaction?>(null) }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    val sourceForEdit = original ?: state.transactions.find { it.id == editingId }
    val eligible = state.accounts.filter { it.deleted_at == null &&
        (it.archived_at == null || it.id == sourceForEdit?.entries?.firstOrNull()?.account_id) &&
        (editingId == null || sourceForEdit == null || it.currency == sourceForEdit.entries.first().currency) }
    var kind by remember { mutableStateOf("expense") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var occurred by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())) }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var error by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf(listOf(AllocationDraft())) }
    var selectedTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val account = eligible.find { it.id == accountId } ?: if (accountId == null) eligible.firstOrNull() else null
    LaunchedEffect(state.financialCommand?.commandId, state.accounts) {
        val command = state.financialCommand
        if (command == null && state.message == "Операция сохранена" && editingId == null) {
            parts = parts.map { it.copy(id = UUID.randomUUID().toString()) }
        }
        if (command != null && command.method in listOf("POST", "PUT") && command.path.contains("/transactions")) {
            val commandKind = ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content
            if (commandKind !in listOf("expense", "income")) return@LaunchedEffect
            if (command.method == "PUT") {
                editingId = command.path.substringAfterLast('/')
                editingVersion = ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("expected_version").jsonPrimitive.content
                original = state.transactions.find { it.id == editingId } ?: original
            }
            kind = ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content
            fun restore(id: String, minor: String, instant: String, timezone: String, memo: String, recipient: String) {
                accountId = id; note = memo; payee = recipient; zone = timezone; occurred = openingLocal(instant, timezone)
                val currency = state.accounts.find { it.id == id }?.currency
                if (currency != null) amount = Money.display(minor, currency).removeSuffix(" $currency").replace('−', '-')
            }
            if (kind == "expense") {
                val body = if (command.method == "PUT") {
                    val edit = decodeResponse<ExpenseReplace>(command.body)
                    ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), edit.occurred_at, edit.occurred_timezone, edit.note, edit.payee, edit.tag_ids, edit.kind, edit.account_id, edit.amount_minor, edit.allocations))
                } else command.body
                val draft = decodeResponse<ExpenseCreate>(body)
                restore(draft.account_id, draft.amount_minor, draft.occurred_at, draft.occurred_timezone, draft.note, draft.payee)
                selectedTags = draft.tag_ids
                val currency = state.accounts.find { it.id == draft.account_id }?.currency
                if (currency != null) parts = draft.allocations.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, currency).removeSuffix(" $currency")) }
            } else if (kind == "income") {
                val body = if (command.method == "PUT") {
                    val edit = decodeResponse<IncomeReplace>(command.body)
                    ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), edit.occurred_at, edit.occurred_timezone, edit.note, edit.payee, edit.tag_ids, edit.kind, edit.account_id, edit.amount_minor, edit.allocations))
                } else command.body
                val draft = decodeResponse<IncomeCreate>(body)
                restore(draft.account_id, draft.amount_minor, draft.occurred_at, draft.occurred_timezone, draft.note, draft.payee)
                selectedTags = draft.tag_ids
                val currency = state.accounts.find { it.id == draft.account_id }?.currency
                if (currency != null) parts = draft.allocations.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, currency).removeSuffix(" $currency")) }
            }
        }
    }
    Text(if (editingId == null) "Новая операция" else "Изменение операции", style = MaterialTheme.typography.titleLarge)
    Row {
        TextButton(enabled = enabled && editingId == null, onClick = { kind = "expense" }) { Text("Расход") }
        TextButton(enabled = enabled && editingId == null, onClick = { kind = "income" }) { Text("Доход") }
    }
    Text(if (kind == "expense") "Тип операции: Расход" else "Тип операции: Доход")
    eligible.forEach { item ->
        TextButton(enabled = enabled, onClick = { accountId = item.id }) { Text("Для операции: ${item.name} · ${item.currency}") }
    }
    Text(if (account == null) "Обновите счета и выберите активный счет." else "Выбран счет: ${account.name} · ${account.currency}")
    OutlinedTextField(amount, { amount = it }, label = { Text("Сумма операции") }, enabled = enabled, singleLine = true)
    OutlinedTextField(occurred, { occurred = it }, label = { Text("Дата операции (YYYY-MM-DDTHH:MM:SS)") }, enabled = enabled, singleLine = true)
    OutlinedTextField(zone, { zone = it }, label = { Text("Часовой пояс операции") }, enabled = enabled, singleLine = true)
    OutlinedTextField(payee, { payee = it }, label = { Text("Получатель операции") }, enabled = enabled, singleLine = true)
    OutlinedTextField(note, { note = it }, label = { Text("Примечание операции") }, enabled = enabled)
    parts.forEachIndexed { index, part ->
        Text("Часть ${index + 1}: ${categoryPath(part.categoryId, state.categories)}")
        TextButton(enabled = enabled, onClick = { parts = parts.map { if (it.id == part.id) it.copy(categoryId = null) else it } }) { Text("Без категории для части ${index + 1}") }
        state.categories.filter { it.archived_at == null || it.id == part.categoryId }.forEach { category ->
            TextButton(enabled = enabled, onClick = { parts = parts.map { if (it.id == part.id) it.copy(categoryId = category.id) else it } }) { Text("Категория части ${index + 1}: ${categoryPath(category.id, state.categories)}") }
        }
        if (parts.size > 1) {
            OutlinedTextField(part.amount, { value -> parts = parts.map { if (it.id == part.id) it.copy(amount = value) else it } }, label = { Text("Сумма части ${index + 1}") }, enabled = enabled, singleLine = true)
            TextButton(enabled = enabled, onClick = { parts = parts.filterNot { it.id == part.id } }) { Text("Убрать часть ${index + 1}") }
        }
    }
    TextButton(enabled = enabled && parts.size < 100, onClick = { parts = parts + AllocationDraft() }) { Text("Добавить часть операции") }
    state.tags.filter { it.archived_at == null || it.id in selectedTags }.forEach { tag ->
        Row {
            Checkbox(tag.id in selectedTags, { checked -> selectedTags = if (checked) (selectedTags + tag.id).distinct() else selectedTags - tag.id }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Выбрать тег ${tag.name}" })
            Text("Тег операции: ${tag.name}")
        }
    }
    if (error.isNotEmpty()) Text(error)
    Button(enabled = enabled && account != null, onClick = {
        try {
            val selected = requireNotNull(account)
            val minor = Money.minor(amount, selected.currency)
            require(BigInteger(minor).signum() > 0 && note.length <= 2000 && payee.length <= 200)
            val source = sourceForEdit
            val instant = if (source != null && occurred == openingLocal(source.occurred_at, source.occurred_timezone) && zone == source.occurred_timezone) source.occurred_at else openingInstant(occurred, zone)
            val allocations = allocationInputs(minor, selected.currency, parts)
            val body = if (kind == "expense") ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), instant, zone, note, payee, selectedTags, "expense", selected.id, minor, allocations))
                else ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), instant, zone, note, payee, selectedTags, "income", selected.id, minor, allocations))
            val request = if (editingId == null) body else if (kind == "expense") {
                require(source != null && source.parent_transaction_id == null)
                ApiClient.json.encodeToString(ExpenseReplace(instant, zone, note, payee, selectedTags, kind, selected.id, minor, allocations, editingVersion, null))
            } else {
                require(source != null)
                ApiClient.json.encodeToString(IncomeReplace(instant, zone, note, payee, selectedTags, kind, selected.id, minor, allocations, editingVersion))
            }
            error = ""; model.submitTransaction(request, editingId)
            if (editingId == null) parts = parts.map { it.copy(id = UUID.randomUUID().toString()) }
        } catch (e: Exception) { error = if (e is IllegalArgumentException && e.message == "Сумма частей должна точно совпадать с суммой операции.") e.message!! else "Проверьте положительную сумму, счет, дату, часовой пояс и длину текста." }
    }) { Text(if (editingId == null) "Сохранить операцию" else "Сохранить изменения операции") }
    if (editingId != null) {
        val current = state.transactions.find { it.id == editingId }
        if (current != null && current.version != editingVersion) {
            Text("Версия операции на сервере: ${current.version}; версия черновика: $editingVersion")
            Button(enabled = enabled, onClick = { editingVersion = current.version }) { Text("Использовать обновленную версию операции") }
        }
        TextButton(enabled = enabled, onClick = { editingId = null; original = null; amount = ""; note = ""; parts = listOf(AllocationDraft()); selectedTags = emptyList(); error = "" }) { Text("Отменить изменение операции") }
    }
    state.transactionConflict?.let { current ->
        Text("На сервере: ${current.note}; версия ${current.version}; ${current.entries.joinToString { Money.display(it.amount_minor, it.currency) }}")
        Text("Ваш черновик операции: $note; сумма $amount")
    }
    deleting?.let { target ->
        Text("Удаление: ${target.note}; ${target.entries.joinToString { Money.display(it.amount_minor, it.currency) }}")
        Text("Движение будет исключено из остатка. Историю зависимых операций проверит сервер.")
        Button(enabled = enabled, onClick = { model.submitTransaction(ApiClient.json.encodeToString(TransactionDelete(target.version, emptyList())), target.id, delete = true); deleting = null }) { Text("Подтвердить удаление операции") }
        TextButton(enabled = enabled, onClick = { deleting = null }) { Text("Отменить удаление") }
    }
    Text("История операций", style = MaterialTheme.typography.titleLarge)
    Button(enabled = !state.busy, onClick = model::loadTransactions) { Text("Обновить историю") }
    state.transactions.forEach { transaction ->
        val label = when (transaction.kind) {
            "expense" -> "Расход"
            "income" -> "Доход"
            "opening" -> "Начальный остаток"
            "transfer" -> "Перевод"
            "refund" -> "Возврат"
            else -> "Корректировка"
        }
        Text("$label · ${transaction.entries.joinToString { Money.display(it.amount_minor, it.currency) }} · ${transaction.note}")
        if (transaction.kind in listOf("income", "expense") && transaction.parent_transaction_id == null) {
            TextButton(enabled = enabled, onClick = {
                original = transaction; editingId = transaction.id; editingVersion = transaction.version; kind = transaction.kind
                accountId = transaction.entries.first().account_id
                amount = Money.display(transaction.allocations.fold(BigInteger.ZERO) { sum, item -> sum + BigInteger(item.amount_minor) }.toString(), transaction.entries.first().currency).removeSuffix(" ${transaction.entries.first().currency}")
                parts = transaction.allocations.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, transaction.entries.first().currency).removeSuffix(" ${transaction.entries.first().currency}")) }
                selectedTags = transaction.tag_ids
                note = transaction.note; payee = transaction.payee; zone = transaction.occurred_timezone; occurred = openingLocal(transaction.occurred_at, zone); error = ""
            }) { Text("Изменить операцию ${transaction.note}") }
            TextButton(enabled = enabled, onClick = { deleting = transaction }) { Text("Удалить операцию ${transaction.note}") }
        }
        Text("${transaction.occurred_at} · ${transaction.occurred_timezone} · ${if (transaction.status == "posted") "Проведена" else "Ожидает проведения"}")
    }
}
