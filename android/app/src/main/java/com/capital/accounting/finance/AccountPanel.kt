package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val guard = rememberDraftGuard("account", enabled, render = false)
    val scope = LocalFinancialDraftScope.current
    val navigation = LocalFinancialEditor.current
    val section = LocalFinancialSection.current
    var accountFilter by rememberSaveable(state.origin, state.auth?.profile?.id, state.auth?.workspace?.id) { mutableStateOf("active") }
    val editorOpen = navigation.value == "account"
    var handledReceipt by remember { mutableStateOf(state.collectionReceipt?.commandId) }
    var editingId by rememberFinancialDraft<String?>("account.editingId") { null }
    var editingVersion by rememberFinancialDraft("account.editingVersion") { "" }
    var editingBalance by rememberFinancialDraft("account.editingBalance") { "0" }
    var name by rememberFinancialDraft("account.name") { "" }
    var type by rememberFinancialDraft("account.type") { "bank" }
    var currency by rememberFinancialDraft("account.currency") { "EUR" }
    var balance by rememberFinancialDraft("account.balance") { "0" }
    var opened by rememberFinancialDraft("account.opened") { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date()) }
    var zone by rememberFinancialDraft("account.zone") { TimeZone.getDefault().id }
    var archived by rememberFinancialDraft("account.archived") { false }
    var error by remember { mutableStateOf("") }
    fun reset() {
        editingId = null; editingVersion = ""; editingBalance = "0"; name = ""; type = "bank"; currency = "EUR"; balance = "0"
        opened = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date()); zone = TimeZone.getDefault().id; archived = false; error = ""
    }
    fun close() {
        if (enabled && !scope.saving) guard.request { reset(); navigation.value = null }
    }
    fun createAccount() { guard.request { reset(); navigation.value = "account" } }
    HandleFinancialCreate("account", enabled) { createAccount() }
    LaunchedEffect(state.financialCommand?.commandId, state.collectionReceipt?.commandId) {
        val command = state.financialCommand
        if (command == null && state.collectionReceipt?.commandId != handledReceipt) {
            val receipt = state.collectionReceipt
            handledReceipt = receipt?.commandId
            if (receipt?.collection == "accounts" && (receipt.recordId == editingId || (receipt.created && editingId == null))) {
                if (editingId != null) receipt.result.accounts.single { it.id == editingId }.let {
                    editingVersion = it.version; currency = it.currency; editingBalance = it.posted_balance_minor
                }
                guard.markClean()
                if (navigation.value == "account") navigation.value = null
            }
        }
        if (command?.method == "PUT" && command.path.contains("/accounts/")) {
            navigation.value = "account"
            val draft = decodeResponse<AccountUpdate>(command.body)
            editingId = command.path.substringAfterLast('/'); editingVersion = draft.expected_version
            name = draft.name; type = draft.type; archived = draft.archived
        } else if (command?.method == "POST" && command.path.endsWith("/accounts")) {
            navigation.value = "account"
            val draft = decodeResponse<AccountCreate>(command.body)
            name = draft.name; type = draft.type; currency = draft.currency
            zone = draft.occurred_timezone; opened = openingLocal(draft.opened_at, zone)
            balance = Money.display(draft.opening_balance_minor, draft.currency).removeSuffix(" ${draft.currency}").replace('−', '-')
        }
    }
    if (navigation.value == null) Button(enabled = !state.busy, onClick = model::loadAccounts) { Text("Обновить счета") }
    if (navigation.value == null) FinancialCommandStatus(model, "Ваш черновик: $name, $type; ${if (archived) "в архив" else "активен"}")
    if (!editorOpen && section.value == "accounts") {
        Text("Счета", style = MaterialTheme.typography.titleLarge)
        Text("Показаны счета: ${when (accountFilter) { "archived" -> "архивные"; "all" -> "все"; else -> "активные" }}")
        listOf("active" to "Активные счета", "archived" to "Архивные счета", "all" to "Все счета").forEach { (value, label) ->
            TextButton(onClick = { accountFilter = value }) { Text(label) }
        }
        val visibleAccounts = state.accounts.filter { when (accountFilter) { "archived" -> it.archived_at != null; "all" -> true; else -> it.archived_at == null } }
        Text("Итоги показанных счетов по валютам")
        visibleAccounts.groupBy { it.currency }.toSortedMap().forEach { (currency, accounts) ->
            Text("Итого: ${Money.displayTotal(accounts.map { it.posted_balance_minor }, currency)}")
        }
        if (state.accountsLoaded && visibleAccounts.isEmpty() && !state.busy) Text(if (state.accounts.isEmpty()) "Создайте первый счет" else "В выбранном фильтре счетов нет.")
        visibleAccounts.forEach { account ->
            Text("${account.name}${if (account.archived_at != null) " · В архиве" else ""}")
            Text("Тип: ${when (account.type) { "bank" -> "Банк"; "card" -> "Карта"; else -> "Наличные" }} · Валюта: ${account.currency}")
            Text(Money.display(account.posted_balance_minor, account.currency))
            TextButton(enabled = !state.busy && state.persisted, onClick = {
                model.applyHistoryFilter(TransactionFilter(accountId = account.id))
                section.value = "operations"
            }) { Text("Операции счета ${account.name}") }
            Button(enabled = enabled, onClick = { guard.request { editingId = account.id; editingVersion = account.version; currency = account.currency; editingBalance = account.posted_balance_minor; name = account.name; type = account.type; archived = account.archived_at != null; error = ""; navigation.value = "account"  } }) { Text("Изменить счет ${account.name}") }
        }
        Button(enabled = enabled && !scope.saving, onClick = ::createAccount) { Text("Добавить счет") }
        TextButton(enabled = enabled && !scope.saving, onClick = { navigation.value = "account" }) { Text("Открыть черновик счета") }
        DraftGuardControls(guard, "account", enabled) { navigation.value = "account" }
    } else if (editorOpen) Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Редактор счета", style = MaterialTheme.typography.headlineLarge)
                state.auth?.let { Text("Вы вошли: ${it.profile.email}") }
                Button(enabled = enabled && !scope.saving, onClick = ::close) { Text("Вернуться к счетам") }
                Button(enabled = !state.busy, onClick = model::loadAccounts) { Text("Обновить счета") }
                if (state.busy) CircularProgressIndicator()
                if (state.message.isNotEmpty()) Text(state.message)
                if (scope.failed) {
                    Text("Не удалось сохранить черновики на устройстве. Повторите сохранение.")
                    Button(enabled = !scope.saving, onClick = { scope.persist(retry = true) }) { Text("Повторить локальное сохранение") }
                }
                DraftGuardControls(guard, "account", enabled)
                FinancialCommandStatus(model, "Ваш черновик: $name, $type; ${if (archived) "в архив" else "активен"}")
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
                if (editingId != null) TextButton(enabled = enabled, onClick = { guard.request { reset() } }) { Text("Новый счет вместо изменения") }
            }
        }
    }
}
