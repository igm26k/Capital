package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.capital.accounting.api.Transaction
import com.capital.accounting.api.TransactionFilter
import java.math.BigInteger

/** The local projection contains the complete dataset, including queued changes. */
fun summaryOperations(items: List<Transaction>, filter: TransactionFilter?): List<Transaction> = items.filter { item ->
    item.kind in setOf("expense", "income", "refund") && (filter == null ||
        (filter.accountId == null || item.entries.any { it.account_id == filter.accountId }) &&
        (filter.categoryId == null || item.allocations.any { it.category_id == filter.categoryId }) &&
        (filter.tagId == null || filter.tagId in item.tag_ids) &&
        (filter.kind == null || item.kind == filter.kind) && (filter.status == null || item.status == filter.status) &&
        (filter.from == null || summaryInstant(item.occurred_at) >= summaryInstant(filter.from)) &&
        (filter.to == null || summaryInstant(item.occurred_at) < summaryInstant(filter.to)) &&
        (item.note.contains(filter.search, ignoreCase = true) || item.payee.contains(filter.search, ignoreCase = true)))
}
private fun summaryInstant(value: String) = value.take(19) + value.drop(19).removeSuffix("Z").removePrefix(".").padEnd(6, '0')
fun operationTotals(items: List<Transaction>): Map<String, Pair<BigInteger, BigInteger>> {
    val totals = linkedMapOf<String, Pair<BigInteger, BigInteger>>()
    items.filter { it.kind in setOf("expense", "income", "refund") }.forEach { item -> item.entries.forEach { entry ->
        val (income, expense) = totals[entry.currency] ?: (BigInteger.ZERO to BigInteger.ZERO)
        val value = BigInteger(entry.amount_minor)
        totals[entry.currency] = if (item.kind == "income") (income + value) to expense else income to (expense - value)
    } }
    return totals
}
@Composable
fun MinimalSummary(items: List<Transaction>, filter: TransactionFilter?) {
    val totals = operationTotals(summaryOperations(items, filter))
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (totals.isEmpty()) Text("Нет доходов и расходов", fontSize = 12.sp)
            totals.forEach { (currency, amounts) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple("Расходы", -amounts.second, Color(0xffd76c78)), Triple("Доходы", amounts.first, Color(0xff37ab7a)), Triple("Баланс периода", amounts.first - amounts.second, MaterialTheme.colorScheme.onSurface)).forEach { (title, value, color) ->
                        Column(Modifier.weight(1f)) { Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(capitalMoney(value.toString(), currency), fontSize = 12.sp, color = color) }
                    }
                }
            }
        }
    }
    Text("Включая предварительные · без переводов", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
