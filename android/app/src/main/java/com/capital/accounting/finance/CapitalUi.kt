package com.capital.accounting.finance

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import java.math.BigInteger

val LocalSettingsGroup = staticCompositionLocalOf { "profile" }
val capitalAccountColors = listOf(0xff167b68, 0xff2463af, 0xff8545a4, 0xffb64a32, 0xff466d30, 0xff3c578d).map { Color(it) }
fun capitalAccountColor(id: String): Color = capitalAccountColors[id.sumOf { it.code } % capitalAccountColors.size]
fun capitalColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xffa3a8ff), onPrimary = Color(0xff141c35), primaryContainer = Color(0xff484982), onPrimaryContainer = Color(0xfff0eeff),
    onSurface = Color(0xfff1f2fa), background = Color(0xff111a2e), surface = Color(0xff202940), surfaceVariant = Color(0xff27314a), onSurfaceVariant = Color(0xffbbc2d7), outline = Color(0xff414c68)
) else lightColorScheme(
    primary = Color(0xff6169bb), onPrimary = Color.White, primaryContainer = Color(0xffe3e2fa), onPrimaryContainer = Color(0xff34365f),
    background = Color(0xfff5f6fb), surface = Color.White, onSurface = Color(0xff20283c), surfaceVariant = Color(0xffedf0f8), onSurfaceVariant = Color(0xff626b80), outline = Color(0xffd0d5e3)
)
// CSS uses normal line-height and no Material letter spacing. Explicit compact
// font sizes must not inherit a 24sp body line box from the Material defaults.
val capitalTypography = Typography().let { defaults ->
    Typography(
        displayLarge = defaults.displayLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        displayMedium = defaults.displayMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        displaySmall = defaults.displaySmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        headlineLarge = defaults.headlineLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        headlineMedium = defaults.headlineMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        headlineSmall = defaults.headlineSmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        titleLarge = defaults.titleLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        titleMedium = defaults.titleMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        titleSmall = defaults.titleSmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        bodyLarge = defaults.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        bodyMedium = defaults.bodyMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        bodySmall = defaults.bodySmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        labelLarge = defaults.labelLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        labelMedium = defaults.labelMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp),
        labelSmall = defaults.labelSmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified, letterSpacing = 0.sp)
    )
}
fun capitalMoney(minor: String, currency: String): String {
    val raw = Money.displayAggregate(minor, currency).removeSuffix(" $currency")
    val symbol = when(currency) { "EUR" -> "€"; "USD" -> "$"; "RUB" -> "₽"; "GBP" -> "£"; "JPY" -> "¥"; else -> currency }
    return "$raw $symbol"
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
fun CapitalSelect(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, optionIcon: (@Composable (String) -> Unit)? = null, optionColor: ((String) -> Color)? = null, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { query = ""; expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                optionIcon?.invoke(value)
                Text(options.find { it.first == value }?.second ?: "Выберите", modifier = Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾")
            }
            if (expanded && enabled) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val scroll = rememberScrollState()
                val filtered = options.filter { it.second.contains(query, ignoreCase = true) }
                LaunchedEffect(query) { scroll.scrollTo(0) }
                Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModalHeader({ Text(label, style = MaterialTheme.typography.titleLarge) }, { expanded = false })
                        OutlinedTextField(query, { query = it }, label = { Text("Поиск: $label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Найдено: ${filtered.size}", style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 16.dp)) {
                                if (filtered.isEmpty()) Text("Ничего не найдено", modifier = Modifier.padding(16.dp))
                                var previousGroup = ""
                                filtered.forEach { (id, title) ->
                                    val group = if (label.startsWith("Категория")) title.substringBefore(" / ") else ""
                                    if (group.isNotEmpty() && group != previousGroup) {
                                        Text(group, color = optionColor?.invoke(id) ?: MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
                                        previousGroup = group
                                    }
                                    DropdownMenuItem(modifier = Modifier.fillMaxWidth(), leadingIcon = if (optionIcon != null) { { optionIcon(id) } } else null, text = { Text(title) }, onClick = { expanded = false; onSelect(id) })
                                }
                            }
                            val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = .35f)
                            val thumbColor = MaterialTheme.colorScheme.primary
                            if (scroll.maxValue > 0) Canvas(Modifier.matchParentSize()) {
                                val trackWidth = 6.dp.toPx()
                                val viewport = scroll.viewportSize.toFloat()
                                val thumbHeight = (size.height * viewport / (viewport + scroll.maxValue)).coerceIn(24.dp.toPx().coerceAtMost(size.height), size.height)
                                val top = (size.height - thumbHeight) * scroll.value.toFloat() / scroll.maxValue
                                drawRoundRect(trackColor, Offset(size.width - trackWidth, 0f), Size(trackWidth, size.height), CornerRadius(trackWidth))
                                drawRoundRect(thumbColor, Offset(size.width - trackWidth, top), Size(trackWidth, thumbHeight), CornerRadius(trackWidth))
                            }
                        }
                        TextButton(onClick = { expanded = false }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountSelect(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    CapitalSelect(label, value, options, enabled, optionIcon = { id ->
        if (id.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).background(capitalAccountColor(id), androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(8.dp))
        }
    }, onSelect = onSelect)
}

// Dimensions and paths mirror web/src/style.css and shared/Icon.tsx.
@Composable
fun CapitalIcon(name: String, modifier: Modifier = Modifier.size(24.dp), color: Color = LocalContentColor.current) {
    val source = when (name) {
        "search" -> "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14M15 15l6 6"
        "close" -> "M6 6l12 12M18 6L6 18"
        "back" -> "M15 5l-7 7 7 7"
        "drag" -> "M9 5h.01M15 5h.01M9 12h.01M15 12h.01M9 19h.01M15 19h.01"
        "food" -> "M4 3v6a2 2 0 0 0 4 0V3M6 3v18M16 3v18M16 3c5 4 5 9 0 9"
        "shop" -> "M4 7h16l1 14H3L4 7M8 7V5a4 4 0 0 1 8 0v2"
        "home" -> "M3 11l9-8 9 8M5 10v11h14V10M10 21v-7h4v7"
        "transport" -> "M4 15l1-7h14l1 7M3 15h18v5H3zM6 20v2M18 20v2M6 17h2M16 17h2"
        "health" -> "M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"
        "travel" -> "M3 10l7 2 5-9 3 1-3 9 6 4-1 2-8-2-5 4-2-1 2-5-5-3z"
        "education" -> "M2 8l10-5 10 5-10 5-10-5M6 10v7q6 5 12 0v-7M22 8v9"
        "leisure" -> "M5 7h14l3 13h-5l-3-4h-4l-3 4H2L5 7M6 11v4M4 13h4M16 12h.01M19 14h.01"
        "work" -> "M3 7h18v14H3zM8 7V3h8v4M3 12h18M10 12v3h4v-3"
        "category" -> "M12 2l10 10-10 10L2 12 12 2M12 8v8M8 12h8"
        "settings" -> "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z"
        "plus" -> "M12 5v14M5 12h14"
        "bank" -> "M3 9l9-6 9 6H3M5 10v8M10 10v8M14 10v8M19 10v8M3 21h18"
        "records" -> "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"
        "arrow" -> "M5 12h14M14 7l5 5-5 5"
        "transfer" -> "M4 8h16l-4-4M20 16H4l4 4"
        "income", "refund" -> "M7 7l10 10M7 17V7h10"
        "menu" -> "M4 6h16M4 12h16M4 18h16"
        else -> "M7 7l10 10M7 17h10V7"
    }
    val path = remember(source) { PathParser().parsePathString(source).toPath() }
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
fun CapitalAccountGrid(accounts: List<Account>, enabled: Boolean, editing: Boolean, onAdd: () -> Unit, onOpen: (Account) -> Unit, onEdit: (Account) -> Unit) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 668.dp) 4 else 3
        val cells: List<Account?> = accounts + listOf(null)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    row.forEach { account ->
                        if (account == null) OutlinedCard(onClick = onAdd, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp), shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, Color(0xff9aaccb)), colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                CapitalIcon("plus", Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                                Text(if (editing) "Добавить счет" else "Добавить операцию", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        } else Card(modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(9.dp),
                            colors = CardDefaults.cardColors(containerColor = capitalAccountColor(account.id), contentColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onOpen(account) }.padding(8.dp)) {
                                Text(account.name, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(5.dp))
                                Text(capitalMoney(account.posted_balance_minor, account.currency), fontSize = 12.sp, lineHeight = 14.4.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (editing) Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Color(0x15000000)).clickable(enabled = enabled) { onEdit(account) }, contentAlignment = Alignment.Center) {
                                Text("Изменить", fontSize = 10.sp)
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
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                if (item.kind in listOf("expense", "income")) CategoryIcon(item.allocations.firstOrNull()?.category_id, state.categories) else CapitalIcon(if (item.kind in listOf("opening", "adjustment")) "bank" else item.kind, Modifier.size(19.dp), MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 12.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val accounts = item.entries.sortedBy { BigInteger(it.amount_minor).signum() }.map { entry -> state.accounts.find { it.id == entry.account_id }?.name ?: entry.currency }.distinct().joinToString(" → ")
                Text(listOf(item.payee, accounts).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 10.sp, lineHeight = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.status == "pending") Text("Предварительная", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.widthIn(max = 125.dp), horizontalAlignment = Alignment.End) {
                item.entries.sortedBy { BigInteger(it.amount_minor).signum() }.forEach { entry ->
                    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val amountColor = when {
                        item.kind in listOf("transfer", "opening", "adjustment") -> Color(if (dark) 0xffa7c8ff else 0xff497bb7)
                        BigInteger(entry.amount_minor).signum() > 0 -> Color(if (dark) 0xff79dba7 else 0xff18734e)
                        else -> Color(if (dark) 0xfff1878e else 0xffbd4652)
                    }
                    Text(capitalMoney(entry.amount_minor, entry.currency), fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = amountColor)
                }
                Text(operationDate(item.occurred_at).takeLast(5), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "⌃" else "⌄", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("expense" to "Расход", "income" to "Доход", "transfer" to "Перевод").forEach { (value, label) ->
                    TextButton(enabled = enabled, onClick = { if (value != type) router.operationTarget = value }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(4.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (value == type) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)) { Text(label, fontSize = 12.sp) }
                }
            }
        }
        TextButton(enabled = enabled, onClick = { router.operationTarget = "adjustment" }) { Text("Корректировка остатка", fontSize = 11.sp) }
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

@Composable
fun CapitalRecentRow(model: AuthViewModel, item: Transaction) {
    val state = model.state
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val positive = item.kind in listOf("income", "refund")
    val background = when (item.kind) {
        "income", "refund" -> if (dark) 0xff23473a else 0xffdef3e9
        "transfer" -> if (dark) 0xff283e56 else 0xffe7edfd
        "adjustment" -> if (dark) 0xff3d3351 else 0xffede9f5
        else -> if (dark) 0xff423725 else 0xfffff0d8
    }
    val accent = when (item.kind) {
        "income", "refund" -> if (dark) 0xff79dcb4 else 0xff16846a
        "transfer" -> if (dark) 0xff8ebbf2 else 0xff4879bd
        "adjustment" -> if (dark) 0xffc3a9eb else 0xff8164a9
        else -> if (dark) 0xffe8b35e else 0xffbb7814
    }
    val entry = item.entries.find { BigInteger(it.amount_minor).signum() < 0 } ?: item.entries.firstOrNull()
    val target = item.entries.find { BigInteger(it.amount_minor).signum() > 0 }.takeIf { item.kind == "transfer" }
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { model.openTransactionDetails(item.id) }.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Color(background), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            if (item.kind in listOf("expense", "income")) CategoryIcon(item.allocations.firstOrNull()?.category_id, state.categories) else CapitalIcon(item.kind, Modifier.size(18.dp), Color(accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.payee.ifBlank { operationTitle(item, state.categories) }, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.accounts.find { it.id == entry?.account_id }?.name.orEmpty(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.widthIn(max = 135.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val valueColor = if (item.kind == "transfer") MaterialTheme.colorScheme.onSurfaceVariant else if (positive || entry?.let { BigInteger(it.amount_minor).signum() > 0 } == true) Color(if (dark) 0xff79dba7 else 0xff18734e) else Color(if (dark) 0xfff1878e else 0xffbd4652)
            Text(entry?.let { Money.displayAggregate(it.amount_minor, it.currency) } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = valueColor)
            target?.let { Text("→ ${Money.displayAggregate(it.amount_minor, it.currency)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(openingLocal(item.occurred_at, java.util.TimeZone.getDefault().id).take(10).split('-').reversed().take(2).joinToString("."), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun operationDate(instant: String): String {
    val local = openingLocal(instant, java.util.TimeZone.getDefault().id)
    val date = local.take(10).split('-')
    return if (date.size == 3) "${date[2]}.${date[1]}.${date[0].takeLast(2)}, ${local.drop(11).take(5)}" else local
}
