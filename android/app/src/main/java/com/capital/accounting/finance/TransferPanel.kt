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
fun TransferPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    val accounts = state.accounts.filter { it.deleted_at == null && it.archived_at == null }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf("") }
    var expectedFeeVersion by remember { mutableStateOf<String?>(null) }
    var feeAnchorId by remember { mutableStateOf<String?>(null) }
    var feeDraftId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sourceCurrency by remember { mutableStateOf<String?>(null) }
    var targetCurrency by remember { mutableStateOf<String?>(null) }
    var feeCurrency by remember { mutableStateOf<String?>(null) }
    var preservedInstant by remember { mutableStateOf<String?>(null) }
    var preservedZone by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Transaction?>(null) }
    var deletingFee by remember { mutableStateOf<Transaction?>(null) }
    var sourceId by remember { mutableStateOf<String?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var sourceAmount by remember { mutableStateOf("") }
    var targetAmount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var occurred by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())) }
    var zone by remember { mutableStateOf(TimeZone.getDefault().id) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var feeEnabled by remember { mutableStateOf(false) }
    var feeAccountId by remember { mutableStateOf<String?>(null) }
    var feeAmount by remember { mutableStateOf("") }
    var feeNote by remember { mutableStateOf("") }
    var feeTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var feeParts by remember { mutableStateOf(listOf(AllocationDraft())) }
    var error by remember { mutableStateOf("") }
    val source = accounts.find { it.id == sourceId } ?: if (sourceId == null) accounts.firstOrNull() else null
    val target = accounts.find { it.id == targetId } ?: if (targetId == null) accounts.firstOrNull { it.id != source?.id } else null
    val feeAccount = accounts.find { it.id == feeAccountId } ?: if (feeAccountId == null) source else null
    val sameCurrency = source != null && source.currency == target?.currency
    val receivingAmount = if (sameCurrency) sourceAmount else targetAmount
    LaunchedEffect(state.financialCommand?.commandId, state.accounts, state.transactions) {
        val command = state.financialCommand
        if (command == null) {
            if (editingId != null && state.message == "Операция сохранена" && state.transactions.none { it.id == editingId }) {
                editingId = null; expectedFeeVersion = null; feeAnchorId = null
                sourceCurrency = null; targetCurrency = null; feeCurrency = null
                preservedInstant = null; preservedZone = null; sourceAmount = ""; targetAmount = ""; note = ""; feeEnabled = false
            }
            return@LaunchedEffect
        }
        if (command.method !in listOf("POST", "PUT") || !command.path.contains("/transactions")) return@LaunchedEffect
        if (ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content != "transfer") return@LaunchedEffect
        val draft = if (command.method == "POST") {
            editingId = null; expectedFeeVersion = null; feeAnchorId = null
            decodeResponse<TransferCreate>(command.body)
        } else {
            val replacement = decodeResponse<TransferReplace>(command.body)
            editingId = command.path.substringAfterLast('/')
            editingVersion = replacement.expected_version; expectedFeeVersion = replacement.expected_fee_version
            feeAnchorId = if (replacement.expected_fee_version == null) null else replacement.fee?.id ?: state.transactions.find { it.id == editingId }?.fee_transaction_id
            TransferCreate(editingId!!, replacement.occurred_at, replacement.occurred_timezone, replacement.note, replacement.payee, replacement.tag_ids, replacement.kind, replacement.source_account_id, replacement.target_account_id, replacement.source_amount_minor, replacement.target_amount_minor, replacement.rate, replacement.fee)
        }
        preservedInstant = draft.occurred_at; preservedZone = draft.occurred_timezone
        if (editingId != null) {
            sourceCurrency = state.accounts.find { it.id == draft.source_account_id }?.currency
            targetCurrency = state.accounts.find { it.id == draft.target_account_id }?.currency
        }
        sourceId = draft.source_account_id; targetId = draft.target_account_id
        note = draft.note; payee = draft.payee; tags = draft.tag_ids
        zone = draft.occurred_timezone; occurred = openingLocal(draft.occurred_at, zone)
        fun decimal(minor: String, accountId: String): String? {
            val currency = state.accounts.find { it.id == accountId }?.currency ?: return null
            return Money.display(minor, currency).removeSuffix(" $currency")
        }
        decimal(draft.source_amount_minor, sourceId!!)?.let { sourceAmount = it }
        decimal(draft.target_amount_minor, targetId!!)?.let { targetAmount = it }
        feeEnabled = draft.fee != null
        draft.fee?.let { fee ->
            feeDraftId = fee.id
            if (editingId != null && expectedFeeVersion != null) feeCurrency = state.accounts.find { it.id == fee.account_id }?.currency
            feeAccountId = fee.account_id; feeNote = fee.note; feeTags = fee.tag_ids
            decimal(fee.amount_minor, fee.account_id)?.let { feeAmount = it }
            val currency = state.accounts.find { it.id == fee.account_id }?.currency
            if (currency != null) feeParts = fee.allocations.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, currency).removeSuffix(" $currency")) }
        }
    }
    Text(if (editingId == null) "Новый перевод" else "Изменение перевода", style = MaterialTheme.typography.titleLarge)
    accounts.forEach { item ->
        TextButton(enabled = enabled && (editingId == null || sourceCurrency == item.currency), onClick = { sourceId = item.id }) { Text("Списать со счета: ${item.name} · ${item.currency}") }
        TextButton(enabled = enabled && item.id != source?.id && (editingId == null || targetCurrency == item.currency), onClick = { targetId = item.id }) { Text("Зачислить на счет: ${item.name} · ${item.currency}") }
    }
    Text("Списание: ${source?.name ?: "Выберите счет"}; зачисление: ${target?.name ?: "Выберите другой счет"}")
    OutlinedTextField(sourceAmount, { sourceAmount = it }, label = { Text("Сумма списания перевода") }, enabled = enabled, singleLine = true)
    OutlinedTextField(receivingAmount, { targetAmount = it }, label = { Text("Сумма зачисления перевода") }, enabled = enabled && !sameCurrency, singleLine = true)
    if (sameCurrency) Text("В одной валюте зачисление равно списанию.")
    val rate = try {
        if (source == null || target == null) null else transferRate(Money.minor(sourceAmount, source.currency), Money.minor(receivingAmount, target.currency), source.currency, target.currency)
    } catch (_: Exception) { null }
    rate?.let { Text("Точный курс: ${it.numerator}/${it.denominator} ${target?.currency} за ${source?.currency}") }
    OutlinedTextField(occurred, { occurred = it }, label = { Text("Дата перевода (YYYY-MM-DDTHH:MM:SS)") }, enabled = enabled, singleLine = true)
    OutlinedTextField(zone, { zone = it }, label = { Text("Часовой пояс перевода") }, enabled = enabled, singleLine = true)
    OutlinedTextField(payee, { payee = it }, label = { Text("Получатель перевода") }, enabled = enabled, singleLine = true)
    OutlinedTextField(note, { note = it }, label = { Text("Примечание перевода") }, enabled = enabled)
    state.tags.filter { it.archived_at == null || it.id in tags }.forEach { tag ->
        Row {
            Checkbox(tag.id in tags, { checked -> tags = if (checked) (tags + tag.id).distinct() else tags - tag.id }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Тег перевода ${tag.name}" })
            Text("Тег перевода: ${tag.name}")
        }
    }
    TextButton(enabled = enabled, onClick = { feeEnabled = !feeEnabled }) { Text(if (feeEnabled) "Убрать комиссию перевода" else "Добавить комиссию перевода") }
    if (feeEnabled) {
        Text("Комиссия — отдельный расход, сохраняемый вместе с переводом.")
        accounts.forEach { item -> TextButton(enabled = enabled && (feeCurrency == null || feeCurrency == item.currency), onClick = { feeAccountId = item.id }) { Text("Счет комиссии: ${item.name} · ${item.currency}") } }
        Text("Комиссия списывается: ${feeAccount?.name ?: "Выберите счет"}")
        OutlinedTextField(feeAmount, { feeAmount = it }, label = { Text("Сумма комиссии перевода") }, enabled = enabled, singleLine = true)
        OutlinedTextField(feeNote, { feeNote = it }, label = { Text("Примечание комиссии перевода") }, enabled = enabled)
        feeParts.forEachIndexed { index, part ->
            Text("Часть комиссии ${index + 1}: ${categoryPath(part.categoryId, state.categories)}")
            TextButton(enabled = enabled, onClick = { feeParts = feeParts.map { if (it.id == part.id) it.copy(categoryId = null) else it } }) { Text("Без категории комиссии ${index + 1}") }
            state.categories.filter { it.archived_at == null || it.id == part.categoryId }.forEach { category ->
                TextButton(enabled = enabled, onClick = { feeParts = feeParts.map { if (it.id == part.id) it.copy(categoryId = category.id) else it } }) { Text("Категория комиссии ${index + 1}: ${categoryPath(category.id, state.categories)}") }
            }
            if (feeParts.size > 1) {
                OutlinedTextField(part.amount, { value -> feeParts = feeParts.map { if (it.id == part.id) it.copy(amount = value) else it } }, label = { Text("Сумма части комиссии ${index + 1}") }, enabled = enabled, singleLine = true)
                TextButton(enabled = enabled, onClick = { feeParts = feeParts.filterNot { it.id == part.id } }) { Text("Убрать часть комиссии ${index + 1}") }
            }
        }
        TextButton(enabled = enabled && feeParts.size < 100, onClick = { feeParts = feeParts + AllocationDraft() }) { Text("Добавить часть комиссии") }
        state.tags.filter { it.archived_at == null || it.id in feeTags }.forEach { tag ->
            Row {
                Checkbox(tag.id in feeTags, { checked -> feeTags = if (checked) (feeTags + tag.id).distinct() else feeTags - tag.id }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Тег комиссии ${tag.name}" })
                Text("Тег комиссии: ${tag.name}")
            }
        }
    }
    if (error.isNotEmpty()) Text(error)
    Button(enabled = enabled && source != null && target != null && source.id != target.id && (!feeEnabled || feeAccount != null), onClick = {
        try {
            val from = requireNotNull(source); val to = requireNotNull(target)
            require(from.id != to.id && note.length <= 2000 && payee.length <= 200 && tags.size <= 50)
            val debit = Money.minor(sourceAmount, from.currency); val credit = Money.minor(receivingAmount, to.currency)
            val exactRate = transferRate(debit, credit, from.currency, to.currency)
            val fee = if (!feeEnabled) null else {
                val account = requireNotNull(feeAccount)
                require(feeNote.length <= 2000 && feeTags.size <= 50)
                val minor = Money.minor(feeAmount, account.currency)
                val inputs = allocationInputs(minor, account.currency, feeParts)
                val allocations = if (editingId == null) inputs.map { it.copy(id = UUID.randomUUID().toString()) } else inputs
                FeeInput(if (editingId == null) UUID.randomUUID().toString() else feeDraftId, account.id, minor, allocations, feeNote, feeTags)
            }
            require(editingId == null || (from.currency == sourceCurrency && to.currency == targetCurrency))
            require(fee == null || feeCurrency == null || requireNotNull(feeAccount).currency == feeCurrency)
            require(editingId == null || expectedFeeVersion == null || fee == null || fee.id == feeAnchorId)
            val instant = if (preservedInstant != null && zone == preservedZone && occurred == openingLocal(preservedInstant!!, preservedZone!!)) preservedInstant!! else openingInstant(occurred, zone)
            val request = if (editingId == null) ApiClient.json.encodeToString(TransferCreate(UUID.randomUUID().toString(), instant, zone, note, payee, tags, "transfer", from.id, to.id, debit, credit, exactRate, fee))
                else ApiClient.json.encodeToString(TransferReplace(instant, zone, note, payee, tags, "transfer", from.id, to.id, debit, credit, exactRate, fee, editingVersion, expectedFeeVersion))
            error = ""; model.submitTransaction(request, editingId)
        } catch (_: Exception) { error = "Проверьте разные счета, положительные суммы, точную сумму частей комиссии, дату и длину текста." }
    }) { Text(if (editingId == null) "Сохранить перевод" else "Сохранить изменения перевода") }
    if (editingId != null) {
        val current = state.transactions.find { it.id == editingId }
        val currentFee = state.transactions.find { it.id == current?.fee_transaction_id }
        if (current != null && (current.version != editingVersion || currentFee?.version != expectedFeeVersion)) {
            Text("Версия перевода на сервере: ${current.version}; черновика: $editingVersion")
            Text("Версия комиссии на сервере: ${currentFee?.version ?: "нет"}; черновика: ${expectedFeeVersion ?: "нет"}")
            if (current.fee_transaction_id == feeAnchorId) {
                Button(enabled = enabled && (current.fee_transaction_id == null || currentFee != null), onClick = { editingVersion = current.version; expectedFeeVersion = currentFee?.version }) { Text("Использовать обновленные версии перевода и комиссии") }
            } else Text("Состав комиссии изменился. Отмените изменение и откройте актуальный перевод для новой правки.")
        }
        state.transactionConflict?.takeIf { it.id == editingId }?.let {
            Text("Ваш черновик перевода: $note; списание $sourceAmount; зачисление $receivingAmount")
        }
        TextButton(enabled = enabled, onClick = {
            editingId = null; editingVersion = ""; expectedFeeVersion = null; feeAnchorId = null
            sourceCurrency = null; targetCurrency = null; feeCurrency = null
            preservedInstant = null; preservedZone = null; sourceId = null; targetId = null
            sourceAmount = ""; targetAmount = ""; note = ""; payee = ""; tags = emptyList()
            feeEnabled = false; feeAccountId = null; feeAmount = ""; feeNote = ""; feeTags = emptyList(); feeParts = listOf(AllocationDraft()); feeDraftId = UUID.randomUUID().toString(); error = ""
        }) { Text("Отменить изменение перевода") }
    }
    state.transactionConflict?.takeIf { it.kind == "transfer" }?.let { Text("Перевод на сервере: ${it.note}; версия ${it.version}") }
    deleting?.let { transfer ->
        Text("Удалить перевод: ${transfer.note}")
        Text("Списание и зачисление будут исключены из остатков.")
        deletingFee?.let { Text("Вместе с переводом будет удалена комиссия: ${it.note}; ${it.entries.joinToString { entry -> Money.display(entry.amount_minor, entry.currency) }}") }
        state.transactions.filter { it.kind == "refund" && it.parent_transaction_id in listOf(transfer.id, transfer.fee_transaction_id) }.forEach { Text("Зависимый возврат: ${it.note}. Сначала удалите возврат.") }
        Button(enabled = enabled && (transfer.fee_transaction_id == null || deletingFee?.id == transfer.fee_transaction_id), onClick = {
            val related = deletingFee?.let { listOf(VersionExpectation(it.id, it.version)) } ?: emptyList()
            model.submitTransaction(ApiClient.json.encodeToString(TransactionDelete(transfer.version, related)), transfer.id, delete = true)
            deleting = null; deletingFee = null
        }) { Text("Подтвердить удаление перевода") }
        TextButton(enabled = enabled, onClick = { deleting = null; deletingFee = null }) { Text("Отменить удаление перевода") }
    }
    state.transactions.filter { it.kind == "transfer" }.forEach { transfer ->
        TextButton(enabled = enabled && (transfer.fee_transaction_id == null || state.transactions.any { it.id == transfer.fee_transaction_id }), onClick = {
            val from = transfer.entries.single { it.amount_minor.startsWith("-") }
            val to = transfer.entries.single { !it.amount_minor.startsWith("-") }
            val fee = state.transactions.find { it.id == transfer.fee_transaction_id }
            editingId = transfer.id; editingVersion = transfer.version; expectedFeeVersion = fee?.version; feeAnchorId = transfer.fee_transaction_id
            sourceId = from.account_id; targetId = to.account_id; sourceCurrency = from.currency; targetCurrency = to.currency
            sourceAmount = Money.display(from.amount_minor.removePrefix("-"), from.currency).removeSuffix(" ${from.currency}")
            targetAmount = Money.display(to.amount_minor, to.currency).removeSuffix(" ${to.currency}")
            note = transfer.note; payee = transfer.payee; tags = transfer.tag_ids; zone = transfer.occurred_timezone; occurred = openingLocal(transfer.occurred_at, zone)
            preservedInstant = transfer.occurred_at; preservedZone = zone
            feeEnabled = fee != null; feeCurrency = fee?.entries?.single()?.currency; feeDraftId = fee?.id ?: UUID.randomUUID().toString()
            feeAccountId = fee?.entries?.single()?.account_id; feeAmount = if (fee == null) "" else Money.display(fee.entries.single().amount_minor.removePrefix("-"), fee.entries.single().currency).removeSuffix(" ${fee.entries.single().currency}")
            feeNote = fee?.note ?: ""; feeTags = fee?.tag_ids ?: emptyList()
            feeParts = fee?.allocations?.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, fee.entries.single().currency).removeSuffix(" ${fee.entries.single().currency}")) } ?: listOf(AllocationDraft())
            error = ""
        }) { Text("Изменить перевод ${transfer.note}") }
        TextButton(enabled = enabled, onClick = { deleting = transfer; deletingFee = state.transactions.find { it.id == transfer.fee_transaction_id } }) { Text("Удалить перевод ${transfer.note}") }
    }
}
