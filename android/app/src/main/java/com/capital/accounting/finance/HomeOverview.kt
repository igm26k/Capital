package com.capital.accounting.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeOverview(model: AuthViewModel) {
    val state = model.state
    val auth = state.auth ?: return
    val section = LocalFinancialSection.current
    var periodFilter by remember { mutableStateOf<TransactionFilter?>(null) }
    var period by rememberSaveable { mutableStateOf("30days") }
    var currency by rememberSaveable { mutableStateOf("EUR") }
    var report by remember(state.origin, auth.profile.id, auth.workspace.id, auth.session.id) { mutableStateOf<Report?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.origin, auth.profile.id, auth.workspace.id, auth.session.id, auth.workspace.sync_generation_id, state.offline, state.transactions, state.accounts, period, retry) {
        report = null; error = ""
        if (state.offline) { error = "Обзор расходов доступен при подключении к серверу"; return@LaunchedEffect }
        loading = true
        try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val end = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val start = if (period == "month") (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                else (end.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -30) }
            val from = URLEncoder.encode(format.format(start.time), "UTF-8")
            val to = URLEncoder.encode(format.format(end.time), "UTF-8")
            periodFilter = TransactionFilter(from = format.format(start.time), to = format.format(end.time), status = "posted")
            report = decodeResponse<Report>(ApiClient(state.origin).request("/workspaces/${auth.workspace.id}/reports?from=$from&to=$to&status=posted", session = auth.credential(state.origin)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.status == 401) model.sessionUnauthorized(auth.session.id)
            error = "Не удалось загрузить обзор расходов"
        } finally { loading = false }
    }
    val palette = listOf(0xff26a9de, 0xffffad32, 0xffff724c, 0xff8168cf, 0xff55b69b, 0xffdb77ac).map { Color(it) }
    val incomeColor = if (isSystemInDarkTheme()) Color(0xff6cd2a8) else Color(0xff16846a)
    CapitalOverviewCard("Структура расходов", "expense") {
        val currencies = (state.accounts.filter { it.archived_at == null }.map { it.currency } + report?.currencies.orEmpty().map { it.currency }).distinct().sorted()
        val selected = currency.takeIf { it in currencies } ?: currencies.firstOrNull().orEmpty()
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            var periodsOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { periodsOpen = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (period == "month") "ЭТОТ МЕСЯЦ ▾" else "ПОСЛЕДНИЕ 30 ДНЕЙ ▾", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(periodsOpen, { periodsOpen = false }) {
                    listOf("30days" to "Последние 30 дней", "month" to "Этот месяц").forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { period = value; periodsOpen = false })
                    }
                }
            }
            if (currencies.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f), maxItemsInEachRow = 3) {
                currencies.forEach { code ->
                    TextButton(onClick = { currency = code }, shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 9.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 0.dp).heightIn(min = 44.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (code == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        Text(code, fontSize = 10.sp, fontWeight = if (code == selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (loading) Box(Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) { Text("Загружаем обзор…", fontSize = 13.sp) }
        if (error.isNotEmpty()) {
            Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.offline) TextButton(onClick = { retry++ }) { Text("Повторить загрузку обзора") }
        }
        report?.takeIf { selected.isNotEmpty() }?.let { data ->
            val categories = data.categories.filter { it.currency == selected && BigInteger(it.expense_minor).signum() != 0 }.sortedByDescending { BigInteger(it.expense_minor).abs() }
            val total = data.currencies.find { it.currency == selected }
            val sum = categories.fold(BigInteger.ZERO) { value, item -> value + BigInteger(item.expense_minor).abs() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Расходы", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(Money.displayAggregate(total?.expense_minor ?: "0", selected), fontSize = 18.sp)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Доходы", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(Money.displayAggregate(total?.income_minor ?: "0", selected), fontSize = 18.sp, color = incomeColor)
                }
            }
            val track = if (isSystemInDarkTheme()) Color(0xff2b3a50) else Color(0xffeaf0f8)
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {
                    val inset = size.width * 19f / 160f
                    val diameter = size.width * 122f / 160f
                    val origin = androidx.compose.ui.geometry.Offset(inset, inset)
                    val bounds = androidx.compose.ui.geometry.Size(diameter, diameter)
                    val stroke = Stroke(size.width * 20f / 160f)
                    drawArc(track, -90f, 360f, false, topLeft = origin, size = bounds, style = stroke)
                    var angle = -90f
                    if (sum.signum() > 0) categories.forEachIndexed { index, item ->
                        val sweep = BigDecimal(BigInteger(item.expense_minor).abs()).divide(BigDecimal(sum), MathContext.DECIMAL64).toFloat() * 360f
                        drawArc(categoryAppearance(item.category_id, state.categories).color, angle, sweep, false, topLeft = origin, size = bounds, style = stroke)
                        angle += sweep
                    }
                }
                Column(Modifier.widthIn(max = 125.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (sum.signum() > 0) "Все расходы" else "Нет расходов", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Money.displayAggregate(total?.expense_minor ?: "0", selected), fontSize = 14.sp)
                }
            }
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                categories.forEachIndexed { index, category ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryIcon(category.category_id, state.categories)
                        Text(state.categories.find { it.id == category.category_id }?.name ?: "Без категории", modifier = Modifier.weight(1f), fontSize = 12.sp)
                        Text(Money.displayAggregate(category.expense_minor, selected), fontSize = 11.sp)
                    }
                }
                if (categories.isEmpty()) Text("За выбранный период расходов нет", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (categories.any { BigInteger(it.expense_minor).signum() < 0 }) Text("Возвраты вычтены из расходов. Доли диаграммы показаны по абсолютным суммам категорий.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { periodFilter?.let { model.applyHistoryFilter(it); section.value = "operations" } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(0.dp)) {
                Text("ОПЕРАЦИИ ЗА ПЕРИОД", fontSize = 11.sp, modifier = Modifier.weight(1f)); CapitalIcon("arrow", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun CapitalOverviewCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                CapitalIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun CapitalHomeOverview(model: AuthViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 668.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { HomeOverview(model) }
                Box(Modifier.weight(1f)) { CapitalRecentRecords(model) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeOverview(model)
                CapitalRecentRecords(model)
            }
        }
    }
}

@Composable
private fun CapitalRecentRecords(model: AuthViewModel) {
    val state = model.state
    val section = LocalFinancialSection.current
    CapitalOverviewCard("Последние записи", "records") {
        Spacer(Modifier.height(12.dp))
        if (state.transactions.none { it.status == "posted" && it.deleted_at == null && it.kind != "opening" }) Text("Здесь появятся ваши операции", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        state.transactions.filter { it.status == "posted" && it.deleted_at == null && it.kind != "opening" }.sortedByDescending { it.occurred_at }.take(5).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            CapitalRecentRow(model, item)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        TextButton(onClick = { section.value = "operations" }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(0.dp)) {
            Text("ВСЕ ЗАПИСИ", fontSize = 11.sp, modifier = Modifier.weight(1f)); CapitalIcon("arrow", Modifier.size(18.dp))
        }
    }
}
