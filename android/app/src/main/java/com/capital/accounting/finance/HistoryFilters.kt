package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.TransactionFilter
import com.capital.accounting.auth.AuthViewModel

@Composable
fun HistoryFilters(model: AuthViewModel) {
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
    val enabled = !state.busy && state.persisted
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть фильтры истории" else "Фильтры истории") }
    if (expanded) {
        Text("Период: от включительно, до не включительно. Время UTC, например 2026-10-06T08:00:00Z.")
        OutlinedTextField(search, { search = it }, label = { Text("Поиск по примечанию и получателю") }, enabled = enabled, singleLine = true)
        OutlinedTextField(from, { from = it }, label = { Text("История от (UTC)") }, enabled = enabled, singleLine = true)
        OutlinedTextField(to, { to = it }, label = { Text("История до (UTC)") }, enabled = enabled, singleLine = true)
        OutlinedTextField(limit, { limit = it }, label = { Text("Операций на странице (1–100)") }, enabled = enabled, singleLine = true)
        Text("Счет: ${state.accounts.find { it.id == account }?.name ?: "Все счета"}")
        TextButton(enabled = enabled, onClick = { account = null }) { Text("История: все счета") }
        state.accounts.forEach { item -> TextButton(enabled = enabled, onClick = { account = item.id }) { Text("История: счет ${item.name}") } }
        Text("Категория: ${if (category == null) "Все категории" else categoryPath(category, state.categories)}")
        TextButton(enabled = enabled, onClick = { category = null }) { Text("История: все категории") }
        state.categories.forEach { item -> TextButton(enabled = enabled, onClick = { category = item.id }) { Text("История: категория ${categoryPath(item.id, state.categories)}") } }
        Text("Тег: ${state.tags.find { it.id == tag }?.name ?: "Все теги"}")
        TextButton(enabled = enabled, onClick = { tag = null }) { Text("История: все теги") }
        state.tags.forEach { item -> TextButton(enabled = enabled, onClick = { tag = item.id }) { Text("История: тег ${item.name}") } }
        Text("Тип: ${TransactionFilter.kinds[kind] ?: "Все типы"}")
        TextButton(enabled = enabled, onClick = { kind = null }) { Text("История: все типы") }
        TransactionFilter.kinds.forEach { (value, label) -> TextButton(enabled = enabled, onClick = { kind = value }) { Text("Тип истории: $label") } }
        Text("Статус: ${TransactionFilter.statuses[status] ?: "Все статусы"}")
        TextButton(enabled = enabled, onClick = { status = null }) { Text("История: все статусы") }
        TransactionFilter.statuses.forEach { (value, label) -> TextButton(enabled = enabled, onClick = { status = value }) { Text("Статус истории: $label") } }
        if (error.isNotEmpty()) Text(error)
        Button(enabled = enabled, onClick = {
            try {
                val pageSize = limit.toInt(); require(pageSize in 1..100)
                val filter = TransactionFilter(account, category, tag, from.takeIf { it.isNotEmpty() }, to.takeIf { it.isNotEmpty() }, kind, status, search)
                error = ""; model.applyHistoryFilter(filter, pageSize)
            } catch (_: Exception) { error = "Проверьте UTC-период, длину поиска и размер страницы. Начало должно быть раньше окончания." }
        }) { Text("Применить фильтры истории") }
    }
    if (expanded || state.historyFilter != null) TextButton(enabled = enabled, onClick = {
        account = null; category = null; tag = null; kind = null; status = null; search = ""; from = ""; to = ""; error = ""
        model.clearHistoryFilters()
    }) { Text("Сбросить фильтры истории") }
    if (state.historyFilter != null) Text(if (state.historyFilter.active) "Фильтры истории применены" else "Все операции по страницам")
    if (state.historyError.isNotEmpty()) Text(state.historyError)
    if (state.historyItems != null) Text("Показано операций: ${state.historyItems.size}")
    if (state.historyNextCursor != null) Button(enabled = enabled, onClick = model::nextHistoryPage) { Text("Следующая страница истории") }
    if ((state.historyItems ?: state.transactions).isEmpty()) Text(if (state.historyFilter?.active == true) "По фильтрам операций нет. Измените или сбросьте фильтры." else "Операций пока нет. Добавьте доход или расход.")
}
