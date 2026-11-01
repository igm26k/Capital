package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AccountPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && state.persisted && (!state.financeBlocked || state.canQueueAccounts)
    val guard = rememberDraftGuard("account", enabled, render = false)
    val scope = LocalFinancialDraftScope.current
    val navigation = LocalFinancialEditor.current
    val section = LocalFinancialSection.current
    val router = LocalFinancialCreateRouter.current
    val managing = section.value == "more" && LocalSettingsGroup.current == "accounts"
    var accountFilter by rememberSaveable(state.origin, state.auth?.profile?.id, state.auth?.workspace?.id) { mutableStateOf("active") }
    var draftOrder by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var orderVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val ordered = orderAccounts(state.accounts, draftOrder ?: state.accounts.map { it.id })
    val orderChanged = ordered.map { it.id } != state.accounts.map { it.id }
    LaunchedEffect(state.accountOrder, state.accounts.map { it.id }) {
        if (draftOrder == state.accounts.map { it.id }) { draftOrder = null; orderVersion = null }
    }
    val editorOpen = navigation.value == "account"
    var handledLocalReceipt by remember { mutableStateOf(state.localCollectionReceipt?.actionId) }
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
    val validation = remember(scope, scope.epoch) { FieldValidation() }
    fun reset() {
        editingId = null; editingVersion = ""; editingBalance = "0"; name = ""; type = "bank"; currency = "EUR"; balance = "0"
        opened = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date()); zone = TimeZone.getDefault().id; archived = false; error = ""; validation.reset()
    }
    fun editAccount(account: Account) {
        guard.request { editingId = account.id; editingVersion = account.version; currency = account.currency; editingBalance = account.posted_balance_minor; name = account.name; type = account.type; archived = account.archived_at != null; error = ""; validation.reset(); navigation.value = "account" }
    }
    fun close() {
        if (enabled && !scope.saving) guard.request { reset(); navigation.value = null }
    }
    fun createAccount() { guard.request { reset(); navigation.value = "account" } }
    HandleFinancialCreate("account", enabled) { createAccount() }
    LaunchedEffect(state.financialCommand?.commandId, state.collectionReceipt?.commandId, state.localCollectionReceipt?.actionId) {
        val local = state.localCollectionReceipt
        if (local != null && local.actionId != handledLocalReceipt) {
            handledLocalReceipt = local.actionId
            if (local.collection == "accounts" && (local.recordId == editingId || local.created && editingId == null)) {
                guard.markClean(); navigation.value = null
            }
        }
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
        if (command?.method == "PUT" && command.path.contains("/accounts/") && !command.path.endsWith("/order")) {
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
    if (navigation.value == null && (section.value == "accounts" || managing)) FinancialCommandStatus(model, "Ваш черновик: $name, $type; ${if (archived) "в архив" else "активен"}")
    if (!editorOpen && (section.value == "accounts" || managing)) {
        val settings = managing
        val visibleCount = state.accounts.count { it.deleted_at == null && when (if (managing) accountFilter else "active") { "archived" -> it.archived_at != null; "all" -> true; else -> it.archived_at == null } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (managing) "Управление счетами" else "Список счетов", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("$visibleCount", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        }
        if (settings) {
            CapitalSelect("Показать счета", accountFilter, listOf("active" to "Активные", "archived" to "Архивные", "all" to "Все")) { accountFilter = it }
            TextButton(enabled = !state.busy, onClick = model::loadAccounts) { Text("Обновить счета") }
        }
        val visibleAccounts = (if (managing) ordered else state.accounts).filter { it.deleted_at == null && when (if (managing) accountFilter else "active") { "archived" -> it.archived_at != null; "all" -> true; else -> it.archived_at == null } }
        if (managing) {
            Button(enabled = enabled && !scope.saving, onClick = ::createAccount) { Text("Добавить счет") }
            if (orderChanged) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled && !state.offline && !scope.saving, onClick = { model.reorderAccounts(ordered.map { it.id }, orderVersion ?: state.accountOrder.version) }) { Text("Сохранить порядок") }
                    TextButton(enabled = !state.busy, onClick = { draftOrder = null; orderVersion = null }) { Text("Отменить") }
                }
            }
            AccountReorderList(visibleAccounts, enabled && !state.offline && !scope.saving,
                move = { id, target ->
                    val visibleIDs = visibleAccounts.map { it.id }.toMutableList()
                    val from = visibleIDs.indexOf(id)
                    if (from >= 0 && target in visibleIDs.indices && from != target) {
                        visibleIDs.removeAt(from); visibleIDs.add(target, id)
                        val visibleSet = visibleIDs.toSet()
                        var index = 0
                        val ids = ordered.map { if (it.id in visibleSet) visibleIDs[index++] else it.id }
                        if (orderVersion == null) orderVersion = state.accountOrder.version
                        draftOrder = ids
                    }
                }, edit = ::editAccount, editEnabled = enabled && !scope.saving)
        } else CapitalAccountGrid(visibleAccounts, enabled && !scope.saving, false, { router.request = FinancialCreationRequest(FinancialCreation.EXPENSE) },
            onOpen = { account -> model.applyHistoryFilter(TransactionFilter(accountId = account.id)); section.value = "operations" },
            onEdit = ::editAccount)
        if (visibleAccounts.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(start = 2.dp, top = 14.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Всего", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            visibleAccounts.groupBy { it.currency }.toSortedMap().forEach { (currency, accounts) ->
                Text(Money.displayTotal(accounts.map { it.posted_balance_minor }, currency), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { section.value = "operations" }) { CapitalIcon("records", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("ЗАПИСИ", fontSize = 12.sp); Spacer(Modifier.width(8.dp)); CapitalIcon("arrow", Modifier.size(18.dp)) }
        }
        if (!managing) CapitalHomeOverview(model)
        if (settings) TextButton(enabled = enabled && !scope.saving, onClick = { navigation.value = "account" }) { Text("Открыть черновик счета") }
        if (managing) DraftGuardControls(guard, "account", enabled) { navigation.value = "account" }
    } else if (editorOpen) Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 24).coerceAtLeast(100).dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Редактор счета", style = MaterialTheme.typography.headlineLarge)
                Button(enabled = enabled && !scope.saving, onClick = ::close) { Text("Отмена") }
                Button(enabled = !state.busy, onClick = model::loadAccounts) { Text("Обновить счета") }

                if (state.message.isNotEmpty()) Text(state.message)
                if (scope.failed) {
                    Text("Не удалось сохранить черновики на устройстве. Повторите сохранение.")
                    Button(enabled = !scope.saving, onClick = { scope.persist(retry = true) }) { Text("Повторить локальное сохранение") }
                }
                DraftGuardControls(guard, "account", enabled)
                FinancialCommandStatus(model, "Ваш черновик: $name, $type; ${if (archived) "в архив" else "активен"}")
                Text(if (editingId == null) "Новый счет" else "Изменить счет", style = MaterialTheme.typography.titleMedium)
                ValidatedTextField(name, { name = it }, if (editingId == null) "Название счета" else "Новое название счета", "name", validation, enabled)
                HorizontalDivider()
                Text("Тип счета", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bank" to "Банк", "cash" to "Наличные", "card" to "Карта").forEach { (value, label) ->
                        FilterChip(selected = type == value, enabled = enabled, onClick = { type = value }, label = { Text(label) })
                    }
                }
                if (editingId == null) {
                    Text("Валюта счета", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Money.scales.keys.forEach { code ->
                            FilterChip(selected = currency == code, enabled = enabled, onClick = { currency = code }, label = { Text(code) })
                        }
                    }
                    HorizontalDivider()
                    Text("Начальный остаток и дата открытия", style = MaterialTheme.typography.titleSmall)
                    ValidatedTextField(balance, { balance = it }, "Начальный остаток", "balance", validation, enabled)
                    ValidatedTextField(opened, { opened = it }, "Дата открытия (YYYY-MM-DDTHH:MM:SS)", "opened", validation, enabled)
                    ValidatedTextField(zone, { zone = it }, "Часовой пояс открытия", "zone", validation, enabled)
                } else {
                    Text("Валюта: $currency. Остаток: ${Money.display(editingBalance, currency)}")
                    Row { Checkbox(archived, { archived = it }, enabled = enabled); Text("Счет в архиве") }
                    Text("Архив сохраняет историю и остаток. Для новых операций восстановите счет.")
                }
                if (editingId != null) {
                    val current = state.accounts.find { it.id == editingId }
                    if (current != null && current.version != editingVersion) {
                        Text(if (("account" to editingId) in state.localAffected) "Ожидаемая версия счета: ${current.version}; ваш черновик: $editingVersion" else "Текущая версия на сервере: ${current.version}; ваш черновик: $editingVersion")
                        Button(enabled = enabled, onClick = { editingVersion = current.version; currency = current.currency; editingBalance = current.posted_balance_minor }) { Text("Использовать обновленную версию") }
                    }
                }
                if (error.isNotEmpty()) Text(error)
                Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = save@{
                    error = ""
                    if (!validation.submit(accountFieldErrors(name, balance, opened, zone, currency, editingId == null))) return@save
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
