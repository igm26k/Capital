package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AccountPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && state.persisted && !state.financeBlocked
    var editingId by remember(state.auth?.session?.id) { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf("") }
    var editingBalance by remember { mutableStateOf("0") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("bank") }
    var currency by remember { mutableStateOf("EUR") }
    var balance by remember { mutableStateOf("0") }
    var opened by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())) }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var archived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(state.financialCommand?.commandId) {
        val command = state.financialCommand
        if (command?.method == "PUT" && command.path.contains("/accounts/")) {
            val draft = decodeResponse<AccountUpdate>(command.body)
            editingId = command.path.substringAfterLast('/'); editingVersion = draft.expected_version
            name = draft.name; type = draft.type; archived = draft.archived
        } else if (command?.method == "POST" && command.path.endsWith("/accounts")) {
            val draft = decodeResponse<AccountCreate>(command.body)
            name = draft.name; type = draft.type; currency = draft.currency
            zone = draft.occurred_timezone; opened = openingLocal(draft.opened_at, zone)
            balance = Money.display(draft.opening_balance_minor, draft.currency).removeSuffix(" ${draft.currency}").replace('−', '-')
        }
    }
    Text("Счета", style = MaterialTheme.typography.titleLarge)
    Button(enabled = !state.busy, onClick = model::loadAccounts) { Text("Обновить счета") }
    state.financialCommand?.let { command ->
        Text("Сохраненная команда: ${command.commandId}")
        Text("Состояние: ${command.state}")
        if (command.state == "pending" && command.sessionId != state.auth?.session?.id && command.generationId == state.auth?.workspace?.sync_generation_id) {
            Button(enabled = !state.busy, onClick = model::rebindAccountCommand) { Text("Продолжить сохраненную команду в этой сессии") }
        }
        if (command.state == "pending") Button(enabled = !state.busy && command.sessionId == state.auth?.session?.id && command.generationId == state.auth?.workspace?.sync_generation_id, onClick = model::retryAccountCommand) { Text("Повторить сохраненную команду") }
        if (command.state == "confirmed" || command.state == "rejected") Button(enabled = !state.busy, onClick = model::acceptAccountResult) { Text(if (command.state == "confirmed") "Принять подтвержденный результат" else "Обновить данные для новой команды") }
        state.accountConflict?.let { current ->
            Text("На сервере: ${current.name}, ${current.type}, версия ${current.version}; ${if (current.archived_at != null) "в архиве" else "активен"}")
            Text("Ваш черновик: $name, $type; ${if (archived) "в архив" else "активен"}")
        }
    }
    state.accounts.forEach { account ->
        Text("${account.name}${if (account.archived_at != null) " · В архиве" else ""}")
        Text(Money.display(account.posted_balance_minor, account.currency))
        Button(enabled = enabled, onClick = { editingId = account.id; editingVersion = account.version; currency = account.currency; editingBalance = account.posted_balance_minor; name = account.name; type = account.type; archived = account.archived_at != null; error = "" }) { Text("Изменить счет ${account.name}") }
    }
    Text(if (editingId == null) "Новый счет" else "Изменить счет", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(name, { name = it }, label = { Text(if (editingId == null) "Название счета" else "Новое название счета") }, enabled = enabled, singleLine = true)
    Text("Тип счета: $type")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("bank" to "Банк", "cash" to "Наличные", "card" to "Карта").forEach { (value, label) ->
            TextButton(enabled = enabled, onClick = { type = value }) { Text(label) }
        }
    }
    if (editingId == null) {
        Text("Валюта счета: $currency")
        Row { Money.scales.keys.take(3).forEach { code -> TextButton(enabled = enabled, onClick = { currency = code }) { Text(code) } } }
        Row { Money.scales.keys.drop(3).forEach { code -> TextButton(enabled = enabled, onClick = { currency = code }) { Text(code) } } }
        OutlinedTextField(balance, { balance = it }, label = { Text("Начальный остаток") }, enabled = enabled, singleLine = true)
        OutlinedTextField(opened, { opened = it }, label = { Text("Дата открытия (YYYY-MM-DDTHH:MM:SS)") }, enabled = enabled, singleLine = true)
        OutlinedTextField(zone, { zone = it }, label = { Text("Часовой пояс открытия") }, enabled = enabled, singleLine = true)
    } else {
        Text("Валюта: $currency. Остаток: ${Money.display(editingBalance, currency)}")
        Row { Checkbox(archived, { archived = it }, enabled = enabled); Text("Счет в архиве") }
        Text("Архив сохраняет историю и остаток. Для новых операций восстановите счет.")
    }
    if (editingId != null) {
        val current = state.accounts.find { it.id == editingId }
        if (current != null && current.version != editingVersion) {
            Text("Текущая версия на сервере: ${current.version}; ваш черновик: $editingVersion")
            Button(enabled = enabled, onClick = { editingVersion = current.version; currency = current.currency; editingBalance = current.posted_balance_minor }) { Text("Использовать обновленную версию") }
        }
    }
    if (error.isNotEmpty()) Text(error)
    Button(enabled = enabled, onClick = {
        try {
            require(name.isNotBlank() && name.length <= 100) { "Введите название счета до 100 символов." }
            val id = editingId
            val body = if (id == null) {
                require(zone in TimeZone.getAvailableIDs()) { "Проверьте часовой пояс." }
                require(opened.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))) { "Проверьте дату открытия." }
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone(zone) }
                val date = requireNotNull(parser.parse(opened))
                val instant = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
                ApiClient.json.encodeToString(AccountCreate(UUID.randomUUID().toString(), name.trim(), type, currency, instant, zone, Money.minor(balance, currency)))
            } else ApiClient.json.encodeToString(AccountUpdate(editingVersion, name.trim(), type, archived))
            error = ""; model.submitAccount(body, id)
        } catch (_: Exception) { error = "Проверьте название, сумму, дату и часовой пояс счета." }
    }) { Text(if (editingId == null) "Создать счет" else "Сохранить счет") }
    if (editingId != null) TextButton(enabled = enabled, onClick = { editingId = null; name = ""; error = "" }) { Text("Новый счет вместо изменения") }
}
