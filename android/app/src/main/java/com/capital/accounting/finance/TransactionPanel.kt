package com.capital.accounting.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val eligible = state.accounts.filter { it.archived_at == null && it.deleted_at == null }
    var kind by remember { mutableStateOf("expense") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var occurred by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())) }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var error by remember { mutableStateOf("") }
    val account = eligible.find { it.id == accountId } ?: if (accountId == null) eligible.firstOrNull() else null
    LaunchedEffect(state.financialCommand?.commandId, state.accounts) {
        val command = state.financialCommand
        if (command?.method == "POST" && command.path.endsWith("/transactions")) {
            kind = ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content
            fun restore(id: String, minor: String, instant: String, timezone: String, memo: String, recipient: String) {
                accountId = id; note = memo; payee = recipient; zone = timezone; occurred = openingLocal(instant, timezone)
                val currency = state.accounts.find { it.id == id }?.currency
                if (currency != null) amount = Money.display(minor, currency).removeSuffix(" $currency").replace('−', '-')
            }
            if (kind == "expense") {
                val draft = decodeResponse<ExpenseCreate>(command.body)
                restore(draft.account_id, draft.amount_minor, draft.occurred_at, draft.occurred_timezone, draft.note, draft.payee)
            } else if (kind == "income") {
                val draft = decodeResponse<IncomeCreate>(command.body)
                restore(draft.account_id, draft.amount_minor, draft.occurred_at, draft.occurred_timezone, draft.note, draft.payee)
            }
        }
    }
    Text("Новая операция", style = MaterialTheme.typography.titleLarge)
    Row {
        TextButton(enabled = enabled, onClick = { kind = "expense" }) { Text("Расход") }
        TextButton(enabled = enabled, onClick = { kind = "income" }) { Text("Доход") }
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
    Text("Категория: без категории")
    if (error.isNotEmpty()) Text(error)
    Button(enabled = enabled && account != null, onClick = {
        try {
            val selected = requireNotNull(account)
            val minor = Money.minor(amount, selected.currency)
            require(BigInteger(minor).signum() > 0 && note.length <= 2000 && payee.length <= 200)
            val instant = openingInstant(occurred, zone)
            val allocations = listOf(AllocationInput(UUID.randomUUID().toString(), null, minor))
            val body = if (kind == "expense") ApiClient.json.encodeToString(ExpenseCreate(UUID.randomUUID().toString(), instant, zone, note, payee, emptyList(), "expense", selected.id, minor, allocations))
                else ApiClient.json.encodeToString(IncomeCreate(UUID.randomUUID().toString(), instant, zone, note, payee, emptyList(), "income", selected.id, minor, allocations))
            error = ""; model.submitTransaction(body)
        } catch (_: Exception) { error = "Проверьте положительную сумму, счет, дату, часовой пояс и длину текста." }
    }) { Text("Сохранить операцию") }
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
        Text("${transaction.occurred_at} · ${transaction.occurred_timezone} · ${if (transaction.status == "posted") "Проведена" else "Ожидает проведения"}")
    }
}
