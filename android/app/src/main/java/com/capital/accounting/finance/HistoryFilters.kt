package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.TransactionFilter
import com.capital.accounting.auth.AuthViewModel

@Composable
fun HistoryFilters(model: AuthViewModel, onReset: () -> Unit = {}) {
    val state = model.state
    var expanded by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(state.historyFilter?.accountId) }
    var category by remember { mutableStateOf(state.historyFilter?.categoryId) }
    var tag by remember { mutableStateOf(state.historyFilter?.tagId) }
    var kind by remember { mutableStateOf(state.historyFilter?.kind) }
    var status by remember { mutableStateOf(state.historyFilter?.status) }
    var search by remember { mutableStateOf(state.historyFilter?.search ?: "") }
    var from by remember { mutableStateOf(state.historyFilter?.from ?: "") }
    var to by remember { mutableStateOf(state.historyFilter?.to ?: "") }
    var limit by remember { mutableStateOf(state.historyLimit.toString()) }
    var error by remember { mutableStateOf("") }
    val validation = remember(state.origin, state.auth?.profile?.id, state.auth?.workspace?.id) { FieldValidation() }
    LaunchedEffect(state.historyFilter, state.historyLimit) {
        val filter = state.historyFilter
        account = filter?.accountId; category = filter?.categoryId; tag = filter?.tagId
        kind = filter?.kind; status = filter?.status
        search = filter?.search ?: ""; from = filter?.from ?: ""; to = filter?.to ?: ""
        limit = state.historyLimit.toString(); error = ""; validation.reset()
    }
    val enabled = !state.busy && state.persisted
    state.historyFilter?.accountId?.let { id ->
        Text("История счета: ${state.accounts.find { it.id == id }?.name ?: id}")
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть фильтры истории" else "Фильтры истории") }
    if (expanded) {
        Text("Период: от включительно, до не включительно. Время UTC, например 2026-10-06T08:00:00Z.")
        ValidatedTextField(search, { search = it }, "Поиск по примечанию и получателю", "search", validation, enabled)
        ValidatedTextField(from, { from = it }, "История от (UTC)", "from", validation, enabled)
        ValidatedTextField(to, { to = it }, "История до (UTC)", "to", validation, enabled)
        ValidatedTextField(limit, { limit = it }, "Операций на странице (1–100)", "limit", validation, enabled)
        CapitalSelect("Счет", account ?: "", listOf("" to "Все счета") + state.accounts.map { it.id to it.name }, enabled) { account = it.ifEmpty { null } }
        CapitalSelect("Категория", category ?: "", listOf("" to "Все категории") + state.categories.map { it.id to categoryPath(it.id, state.categories) }, enabled) { category = it.ifEmpty { null } }
        CapitalSelect("Тег", tag ?: "", listOf("" to "Все теги") + state.tags.map { it.id to it.name }, enabled) { tag = it.ifEmpty { null } }
        CapitalSelect("Тип", kind ?: "", listOf("" to "Все типы") + TransactionFilter.kinds.toList(), enabled) { kind = it.ifEmpty { null } }
        CapitalSelect("Статус", status ?: "", listOf("" to "Все статусы") + TransactionFilter.statuses.toList(), enabled) { status = it.ifEmpty { null } }
        if (error.isNotEmpty()) Text(error)
        Button(enabled = enabled, onClick = apply@{
            error = ""
            if (!validation.submit(historyFieldErrors(search, from, to, limit))) return@apply
            try {
                val pageSize = limit.toInt(); require(pageSize in 1..100)
                val filter = TransactionFilter(account, category, tag, from.takeIf { it.isNotEmpty() }, to.takeIf { it.isNotEmpty() }, kind, status, search)
                error = ""; model.applyHistoryFilter(filter, pageSize)
            } catch (_: Exception) { error = "Проверьте UTC-период, длину поиска и размер страницы. Начало должно быть раньше окончания." }
        }) { Text("Применить фильтры истории") }
    }
    TextButton(enabled = enabled, onClick = {
        account = null; category = null; tag = null; kind = null; status = null; search = ""; from = ""; to = ""; limit = "25"; expanded = false; error = ""; validation.reset()
        onReset(); model.clearHistoryFilters()
    }) { Text("Сбросить фильтры") }
    if (state.historyFilter != null) Text(if (state.historyFilter.active) "Фильтры истории применены" else "Все операции по страницам")
    if (state.historyError.isNotEmpty()) Text(state.historyError)
    if (state.historyItems != null) Text("Показано операций: ${state.historyItems.size}")
    if (state.historyLocal) Text("История на устройстве${if (state.offline) " · сохранённые данные" else ""}")
    if (state.historyLocalCursor != null) Button(enabled = enabled, onClick = model::nextHistoryPage) { Text("Следующая страница истории") }
    if ((state.historyItems ?: state.transactions).isEmpty()) Text(if (state.historyFilter?.active == true) "По фильтрам операций нет. Измените или сбросьте фильтры." else "Операций пока нет. Добавьте доход или расход.")
}
