package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.util.TimeZone
import java.util.UUID

@Composable
fun AdjustmentPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    var accountId by remember { mutableStateOf<String?>(null) }
    var balanceVersion by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    var error by remember { mutableStateOf("") }
    val account = state.accounts.find { it.id == accountId }
    val edited = state.transactions.find { it.id == editingId }
    val difference = try {
        account?.let { Money.display((BigInteger(Money.minor(target, it.currency)) - BigInteger(it.posted_balance_minor)).toString(), it.currency) }
    } catch (_: Exception) { null }
    LaunchedEffect(state.financialCommand?.commandId, state.accounts, state.transactions) {
        val command = state.financialCommand
        if (command == null) {
            if (editingId != null && state.message == "Операция сохранена") {
                if (edited == null) editingId = null else editingVersion = edited.version
            }
            return@LaunchedEffect
        }
        if (command.method == "POST" && command.path.endsWith("/adjustments")) {
            val draft = decodeResponse<AdjustmentCreate>(command.body)
            editingId = null; accountId = command.path.substringBeforeLast('/').substringAfterLast('/')
            balanceVersion = draft.expected_balance_version; reason = draft.reason; note = draft.note; zone = draft.occurred_timezone
            val currency = state.accounts.find { it.id == accountId }?.currency ?: return@LaunchedEffect
            target = Money.display(draft.target_balance_minor, currency).removeSuffix(" $currency").replace('−', '-')
        } else if (command.method == "PUT" && command.path.contains("/transactions/") && ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content == "adjustment") {
            val draft = decodeResponse<AdjustmentReplace>(command.body)
            editingId = command.path.substringAfterLast('/'); editingVersion = draft.expected_version; note = draft.note
            accountId = state.transactions.find { it.id == editingId }?.entries?.singleOrNull()?.account_id
        }
    }
    Text(if (editingId == null) "Корректировка остатка" else "Изменение примечания корректировки", style = MaterialTheme.typography.titleLarge)
    if (editingId == null) {
        Text("Укажите фактический остаток. Сервер вычислит разницу и зафиксирует текущее время.")
        state.accounts.filter { it.archived_at == null && it.deleted_at == null }.forEach { item ->
            TextButton(enabled = enabled, onClick = {
                accountId = item.id; balanceVersion = item.balance_version
                target = Money.display(item.posted_balance_minor, item.currency).removeSuffix(" ${item.currency}").replace('−', '-')
                error = ""
            }) { Text("Корректировать счет ${item.name} · ${item.currency}") }
        }
        if (account != null) {
            Text("Корректируемый счет: ${account.name}")
            Text("Текущий остаток: ${Money.display(account.posted_balance_minor, account.currency)}")
            OutlinedTextField(target, { target = it }, label = { Text("Фактический остаток") }, enabled = enabled, singleLine = true)
            if (difference != null) Text("Разница нового остатка: $difference")
            else Text("Введите допустимый остаток и разницу в пределах денежного лимита.")
            OutlinedTextField(reason, { reason = it }, label = { Text("Причина корректировки") }, enabled = enabled)
            OutlinedTextField(zone, { zone = it }, label = { Text("Часовой пояс корректировки") }, enabled = enabled, singleLine = true)
            if (account.balance_version != balanceVersion) {
                Text("Версия остатка на сервере: ${account.balance_version}; черновика: $balanceVersion")
                Button(enabled = enabled, onClick = { balanceVersion = account.balance_version }) { Text("Использовать обновленную версию остатка") }
            }
        }
    } else {
        Text("Изменяется только примечание. Сумма, причина, счет и время сохраняются.")
        edited?.let { item ->
            Text("Счет корректировки: ${account?.name ?: "Обновите счета"}")
            Text("Разница корректировки: ${Money.display(item.entries.single().amount_minor, item.entries.single().currency)}")
            Text("Причина: ${item.reason ?: ""}")
            Text("Время корректировки: ${openingLocal(item.occurred_at, item.occurred_timezone)} · ${item.occurred_timezone}")
        }
        if (edited != null && edited.version != editingVersion) {
            Text("Версия корректировки на сервере: ${edited.version}; черновика: $editingVersion")
            Button(enabled = enabled, onClick = { editingVersion = edited.version }) { Text("Использовать обновленную версию корректировки") }
        }
        state.transactionConflict?.takeIf { it.kind == "adjustment" }?.let { Text("Корректировка на сервере: ${it.note}; версия ${it.version}") }
    }
    if (accountId != null || editingId != null) {
        OutlinedTextField(note, { note = it }, label = { Text("Примечание корректировки") }, enabled = enabled)
        if (error.isNotEmpty()) Text(error)
        Button(enabled = enabled && account != null && account.archived_at == null && account.deleted_at == null, onClick = {
            try {
                require(note.length <= 2000)
                if (editingId == null) {
                    val selected = requireNotNull(account)
                    val minor = Money.minor(target, selected.currency)
                    require(difference != null && minor != selected.posted_balance_minor && reason.isNotEmpty() && reason.length <= 500)
                    TimeZone.getTimeZone(zone).also { require(it.id == zone || zone == "UTC") }
                    model.submitAdjustment(ApiClient.json.encodeToString(AdjustmentCreate(UUID.randomUUID().toString(), balanceVersion, minor, reason, note, zone)), selected.id)
                } else model.submitTransaction(ApiClient.json.encodeToString(AdjustmentReplace("adjustment", editingVersion, note)), editingId)
                error = ""
            } catch (_: Exception) { error = "Проверьте остаток, причину, часовой пояс и длину текста. Остаток должен измениться." }
        }) { Text(if (editingId == null) "Сохранить корректировку" else "Сохранить примечание корректировки") }
        TextButton(enabled = enabled, onClick = { accountId = null; editingId = null; note = ""; reason = ""; error = "" }) { Text("Отменить корректировку") }
    }
    deleting?.let { item ->
        Text("Удалить корректировку: ${item.note}")
        Text("Разница корректировки будет исключена из остатка счета.")
        Button(enabled = enabled, onClick = { model.submitTransaction(ApiClient.json.encodeToString(TransactionDelete(item.version, emptyList())), item.id, delete = true); deleting = null }) { Text("Подтвердить удаление корректировки") }
        TextButton(enabled = enabled, onClick = { deleting = null }) { Text("Отменить удаление корректировки") }
    }
    state.transactions.filter { it.kind == "adjustment" }.forEach { item ->
        val active = state.accounts.any { it.id == item.entries.single().account_id && it.archived_at == null && it.deleted_at == null }
        TextButton(enabled = enabled && active, onClick = { editingId = item.id; editingVersion = item.version; accountId = item.entries.single().account_id; note = item.note; error = "" }) { Text("Изменить корректировку ${item.note}") }
        TextButton(enabled = enabled && active, onClick = { deleting = item }) { Text("Удалить корректировку ${item.note}") }
    }
}
