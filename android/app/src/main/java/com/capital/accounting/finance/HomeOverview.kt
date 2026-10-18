package com.capital.accounting.finance

import androidx.compose.foundation.Canvas
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
            report = decodeResponse<Report>(ApiClient(state.origin).request("/workspaces/${auth.workspace.id}/reports?from=$from&to=$to&status=posted", session = auth.credential(state.origin)))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is ApiFailure && e.status == 401) model.sessionUnauthorized(auth.session.id)
            error = "Не удалось загрузить обзор расходов"
        } finally { loading = false }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обзор расходов", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = period == "month", onClick = { period = "month" }, label = { Text("Этот месяц") })
                FilterChip(selected = period == "30days", onClick = { period = "30days" }, label = { Text("30 дней") })
            }
            val currencies = (state.accounts.map { it.currency } + report?.currencies.orEmpty().map { it.currency }).distinct().sorted()
            val selected = currency.takeIf { it in currencies } ?: currencies.firstOrNull().orEmpty()
            if (currencies.isNotEmpty()) CapitalSelect("Валюта", selected, currencies.map { it to it }) { currency = it }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) {
                Text(error, style = MaterialTheme.typography.bodySmall)
                if (!state.offline) TextButton(onClick = { retry++ }) { Text("Повторить") }
            }
            report?.takeIf { selected.isNotEmpty() }?.let { data ->
                val categories = data.categories.filter { it.currency == selected && BigInteger(it.expense_minor).signum() > 0 }.sortedByDescending { BigInteger(it.expense_minor) }
                val total = data.currencies.find { it.currency == selected }
                val sum = categories.fold(BigInteger.ZERO) { value, item -> value + BigInteger(item.expense_minor) }
                val track = MaterialTheme.colorScheme.surfaceVariant
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(156.dp).padding(12.dp)) {
                        drawArc(track, -90f, 360f, false, style = Stroke(24.dp.toPx()))
                        var angle = -90f
                        if (sum.signum() > 0) categories.forEachIndexed { index, item ->
                            val sweep = BigDecimal(item.expense_minor).divide(BigDecimal(sum), MathContext.DECIMAL64).toFloat() * 360f
                            drawArc(capitalAccountColors[index % capitalAccountColors.size], angle, sweep, false, style = Stroke(24.dp.toPx())); angle += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Расходы", style = MaterialTheme.typography.labelMedium)
                        Text(capitalMoney(total?.expense_minor ?: "0", selected), style = MaterialTheme.typography.titleSmall)
                    }
                }
                Text("Доходы: ${capitalMoney(total?.income_minor ?: "0", selected)}", style = MaterialTheme.typography.bodyMedium)
                categories.take(5).forEachIndexed { index, category ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(categoryPath(category.category_id, state.categories), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = capitalAccountColors[index % capitalAccountColors.size])
                        Text(capitalMoney(category.expense_minor, selected), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (categories.isEmpty()) Text("За этот период расходов нет", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
