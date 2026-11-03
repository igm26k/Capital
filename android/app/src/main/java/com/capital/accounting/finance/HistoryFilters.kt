package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val router = LocalFinancialCreateRouter.current
    LaunchedEffect(router.historySearchOpen) { if (router.historySearchOpen) { expanded = true; router.historySearchOpen = false } }
    val enabled = !state.busy && state.persisted
    val active = state.historyFilter?.active == true
    fun reset() {
        account = null; category = null; tag = null; kind = null; status = null
        search = ""; from = ""; to = ""; limit = "25"; expanded = false; error = ""; validation.reset()
        onReset(); model.clearHistoryFilters()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = {
            val current = state.historyFilter ?: TransactionFilter()
            val date = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
            val instant = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(date.time)
            onReset(); model.applyHistoryFilter(current.copy(from = if (current.from == null) instant else null, to = null), state.historyLimit)
        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text(if (state.historyFilter?.from != null || state.historyFilter?.to != null) "Период задан" else "Весь период", fontSize = 11.sp) }
        Box(Modifier.weight(1f)) {
            var choosing by remember { mutableStateOf(false) }
            OutlinedButton(enabled = enabled, onClick = { choosing = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(8.dp)) { Text(state.accounts.find { it.id == state.historyFilter?.accountId }?.name ?: "Все счета", maxLines = 1, fontSize = 11.sp) }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                (listOf("" to "Все счета") + state.accounts.map { it.id to it.name }).forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { choosing = false; onReset(); model.applyHistoryFilter((state.historyFilter ?: TransactionFilter()).copy(accountId = id.ifEmpty { null }), state.historyLimit) }) }
            }
        }
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Открыть фильтры операций" }) { CapitalIcon("settings") }
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(100).dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Фильтры операций", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { expanded = false }, modifier = Modifier.semantics { contentDescription = "Закрыть фильтры" }) { Text("✕") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Период: от включительно, до не включительно. Время UTC, например 2026-10-06T08:00:00Z.")
                    ValidatedTextField(search, { search = it }, "Поиск по примечанию и получателю", "search", validation, enabled)
                    ValidatedTextField(from, { from = it }, "История от (UTC)", "from", validation, enabled)
                    ValidatedTextField(to, { to = it }, "История до (UTC)", "to", validation, enabled)
                    ValidatedTextField(limit, { limit = it }, "Операций на странице (1–100)", "limit", validation, enabled)
                    AccountSelect("Счет", account ?: "", listOf("" to "Все счета") + state.accounts.map { it.id to it.name }, enabled) { account = it.ifEmpty { null } }
                    CapitalSelect("Категория", category ?: "", listOf("" to "Все категории") + state.categories.map { it.id to categoryPath(it.id, state.categories) }, enabled) { category = it.ifEmpty { null } }
                    CapitalSelect("Тег", tag ?: "", listOf("" to "Все теги") + state.tags.map { it.id to it.name }, enabled) { tag = it.ifEmpty { null } }
                    CapitalSelect("Тип", kind ?: "", listOf("" to "Все типы") + TransactionFilter.kinds.toList(), enabled) { kind = it.ifEmpty { null } }
                    CapitalSelect("Статус", status ?: "", listOf("" to "Все статусы") + TransactionFilter.statuses.toList(), enabled) { status = it.ifEmpty { null } }
                    if (error.isNotEmpty()) Text(error)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = enabled, onClick = ::reset, modifier = Modifier.weight(1f)) { Text("Сбросить") }
                    Button(enabled = enabled, modifier = Modifier.weight(1f), onClick = apply@{
                        error = ""
                        if (!validation.submit(historyFieldErrors(search, from, to, limit))) return@apply
                        try {
                            val pageSize = limit.toInt(); require(pageSize in 1..100)
                            val filter = TransactionFilter(account, category, tag, from.takeIf { it.isNotEmpty() }, to.takeIf { it.isNotEmpty() }, kind, status, search)
                            error = ""; model.applyHistoryFilter(filter, pageSize); expanded = false
                        } catch (_: Exception) { error = "Проверьте UTC-период, длину поиска и размер страницы. Начало должно быть раньше окончания." }
                    }) { Text("Применить") }
                }
            }
        }
    }
    if (state.historyError.isNotEmpty()) Text(state.historyError, color = MaterialTheme.colorScheme.error)
    if ((state.historyItems ?: state.transactions).isEmpty()) Text(if (active) "По фильтрам операций нет. Измените или сбросьте фильтры." else "Операций пока нет. Добавьте доход или расход.")
}

@Composable
fun HistoryFooter(model: AuthViewModel) {
    val state = model.state
    if (state.historyLocalCursor != null) TextButton(enabled = !state.busy && state.persisted, onClick = model::nextHistoryPage, modifier = Modifier.fillMaxWidth()) { Text("Показать ещё") }
}
