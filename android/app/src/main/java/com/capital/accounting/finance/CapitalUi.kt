package com.capital.accounting.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import java.math.BigInteger

val LocalSettingsGroup = staticCompositionLocalOf { "profile" }
val capitalAccountColors = listOf(0xff167b68, 0xff2463af, 0xff8545a4, 0xffb64a32, 0xff466d30, 0xff3c578d).map { Color(it) }
fun capitalColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xffadc7ff), onPrimary = Color(0xff172c4f), primaryContainer = Color(0xff293e62), onPrimaryContainer = Color(0xffd8e5ff),
    background = Color(0xff101722), surface = Color(0xff1a2433), surfaceVariant = Color(0xff202d41), onSurfaceVariant = Color(0xffaab9ce), outline = Color(0xff34445c)
) else lightColorScheme(
    primary = Color(0xff365b9d), onPrimary = Color.White, primaryContainer = Color(0xffe4ebf8), onPrimaryContainer = Color(0xff203c70),
    background = Color(0xfff2f5fa), surface = Color.White, surfaceVariant = Color(0xffedf1f8), onSurfaceVariant = Color(0xff5e6f87), outline = Color(0xffdbe2ed)
)
fun capitalMoney(minor: String, currency: String): String {
    val raw = Money.displayAggregate(minor, currency).removeSuffix(" $currency")
    val parts = raw.removePrefix("−").split(',')
    val grouped = parts[0].reversed().chunked(3).joinToString(" ").reversed()
    val symbol = when(currency) { "EUR" -> "€"; "USD" -> "$"; "RUB" -> "₽"; "GBP" -> "£"; "JPY" -> "¥"; else -> currency }
    return (if (raw.startsWith("−")) "−" else "") + grouped + (if (parts.size > 1) ",${parts[1]}" else "") + " $symbol"
}

fun transactionNote(note: String): String {
    val lines = note.lines()
    val source = Regex("Wallet · строка \\d+")
    val credit = Regex("Зачисление Wallet · строка \\d+ · .*")
    return if (lines.none { source.matches(it) || credit.matches(it) }) note else lines.filterNot { source.matches(it) || credit.matches(it) || it.startsWith("Категория Wallet: ") }.joinToString("\n").trim()
}
fun transactionReason(reason: String?): String? = when(reason) {
    "Начальный остаток Wallet" -> "Начальный остаток"
    "Корректировка Wallet: отсутствует направление перевода", "Корректировка Wallet: отсутствует источник поступления" -> "Корректировка остатка"
    else -> reason
}
fun operationTitle(item: Transaction, categories: List<Category>): String = when (item.kind) {
    "expense", "income" -> item.allocations.map { categoryPath(it.category_id, categories) }.distinct().joinToString(" · ").ifEmpty { "Без категории" }
    "transfer" -> "Перевод"; "adjustment" -> "Корректировка"; "opening" -> "Начальный остаток"; "refund" -> "Возврат"; else -> "Операция"
}

@Composable
fun CapitalSelect(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.find { it.first == value }?.second ?: "Выберите", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("▾")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
fun CapitalAccountGrid(accounts: List<Account>, enabled: Boolean, editing: Boolean, onAdd: () -> Unit, onOpen: (Account) -> Unit, onEdit: (Account) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 600.dp) 4 else 3
        val cells: List<Account?> = accounts + listOf(null)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { account ->
                        if (account == null) OutlinedCard(onClick = onAdd, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 60.dp), shape = RoundedCornerShape(10.dp)) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) { Text("+ Счет", style = MaterialTheme.typography.labelLarge) }
                        } else Card(onClick = { if (editing) onEdit(account) else onOpen(account) }, enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 60.dp), shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = capitalAccountColors[account.id.sumOf { it.code } % capitalAccountColors.size], contentColor = Color.White)) {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (editing) "✎ " else "") + account.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(capitalMoney(account.posted_balance_minor, account.currency), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun CapitalOperationRow(model: AuthViewModel, item: Transaction, expandedContent: (@Composable () -> Unit)? = null) {
    val state = model.state
    var expanded by remember(item.id) { mutableStateOf(false) }
    val title = operationTitle(item, state.categories)
    Card(onClick = { if (expandedContent == null) model.openTransactionDetails(item.id) else expanded = !expanded },
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(when(item.kind) { "transfer" -> "⇄"; "adjustment" -> "±"; "income", "refund" -> "↙"; else -> "↗" }, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val accounts = item.entries.sortedBy { BigInteger(it.amount_minor).signum() }.map { entry -> state.accounts.find { it.id == entry.account_id }?.name ?: entry.currency }.distinct().joinToString(" → ")
                Text(listOf(item.payee, accounts).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                transactionNote(item.note).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(horizontalAlignment = Alignment.End) {
                item.entries.sortedBy { BigInteger(it.amount_minor).signum() }.forEach { entry ->
                    Text(capitalMoney(entry.amount_minor, entry.currency), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(openingLocal(item.occurred_at, item.occurred_timezone).take(10).split('-').reversed().joinToString("."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            transactionNote(item.note).takeIf { it.isNotBlank() }?.let { Text(it) }
            transactionReason(item.reason)?.let { Text(it) }
            TextButton(onClick = { model.openTransactionDetails(item.id) }, enabled = !state.busy && state.persisted) { Text("Детали операции") }
            expandedContent?.invoke()
        }
    }
}

@Composable
fun OperationTypeSelect(model: AuthViewModel, type: String, enabled: Boolean) {
    val router = LocalFinancialCreateRouter.current
    CapitalSelect("Тип операции", type, listOf("expense" to "Расход", "income" to "Доход", "transfer" to "Перевод", "adjustment" to "Корректировка"), enabled) { value ->
        if (value != type) router.operationTarget = value
    }
}

@Composable
fun HandleOperationType(prefix: String, editing: Boolean, enabled: Boolean, guard: DraftGuard, open: (String, Boolean) -> Unit) {
    val router = LocalFinancialCreateRouter.current
    val navigation = LocalFinancialEditor.current
    val scope = LocalFinancialDraftScope.current
    val selected = router.operationTarget
    LaunchedEffect(selected, enabled, scope.saving, scope.failed) {
        val destination = when (selected) { "expense", "income" -> "transaction"; else -> selected }
        if (selected != null && destination == prefix && enabled && !scope.saving && !scope.failed) {
            router.operationTarget = null
            val action = { open(selected, editing); navigation.value = prefix }
            if (editing) guard.request(action) else action()
        }
    }
    if (guard.pending != null && navigation.value != prefix) DraftGuardControls(guard, prefix, enabled)
}
