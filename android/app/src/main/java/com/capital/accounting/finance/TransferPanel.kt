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
    LaunchedEffect(state.financialCommand?.commandId, state.accounts) {
        val command = state.financialCommand ?: return@LaunchedEffect
        if (command.method != "POST" || !command.path.endsWith("/transactions")) return@LaunchedEffect
        if (ApiClient.json.parseToJsonElement(command.body).jsonObject.getValue("kind").jsonPrimitive.content != "transfer") return@LaunchedEffect
        val draft = decodeResponse<TransferCreate>(command.body)
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
            feeAccountId = fee.account_id; feeNote = fee.note; feeTags = fee.tag_ids
            decimal(fee.amount_minor, fee.account_id)?.let { feeAmount = it }
            val currency = state.accounts.find { it.id == fee.account_id }?.currency
            if (currency != null) feeParts = fee.allocations.map { AllocationDraft(it.id, it.category_id, Money.display(it.amount_minor, currency).removeSuffix(" $currency")) }
        }
    }
    Text("Новый перевод", style = MaterialTheme.typography.titleLarge)
    accounts.forEach { item ->
        TextButton(enabled = enabled, onClick = { sourceId = item.id }) { Text("Списать со счета: ${item.name} · ${item.currency}") }
        TextButton(enabled = enabled && item.id != source?.id, onClick = { targetId = item.id }) { Text("Зачислить на счет: ${item.name} · ${item.currency}") }
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
        accounts.forEach { item -> TextButton(enabled = enabled, onClick = { feeAccountId = item.id }) { Text("Счет комиссии: ${item.name} · ${item.currency}") } }
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
                val allocations = allocationInputs(minor, account.currency, feeParts).map { it.copy(id = UUID.randomUUID().toString()) }
                FeeInput(UUID.randomUUID().toString(), account.id, minor, allocations, feeNote, feeTags)
            }
            val request = TransferCreate(UUID.randomUUID().toString(), openingInstant(occurred, zone), zone, note, payee, tags, "transfer", from.id, to.id, debit, credit, exactRate, fee)
            error = ""; model.submitTransaction(ApiClient.json.encodeToString(request))
        } catch (_: Exception) { error = "Проверьте разные счета, положительные суммы, точную сумму частей комиссии, дату и длину текста." }
    }) { Text("Сохранить перевод") }
}
