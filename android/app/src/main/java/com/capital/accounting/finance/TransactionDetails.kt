package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.capital.accounting.api.Transaction
import com.capital.accounting.api.TransactionFilter
import com.capital.accounting.auth.AuthViewModel
import java.math.BigInteger

@Composable
fun TransactionDetails(model: AuthViewModel) {
    val state = model.state
    Dialog(onDismissRequest = { model.backTransactionDetails() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Детали операции", style = MaterialTheme.typography.headlineLarge)
                Button(enabled = !state.busy, onClick = model::closeTransactionDetails) { Text("Вернуться к истории") }
                if (state.detailStack.size > 1) TextButton(enabled = !state.busy, onClick = model::backTransactionDetails) { Text("Назад к предыдущим деталям") }
                Button(enabled = !state.busy, onClick = model::refreshTransactionDetails) { Text("Обновить детали") }
                if (state.busy) CircularProgressIndicator()
                if (state.detailMissing) Text("Операция удалена или недоступна.")
                if (state.detailError.isNotEmpty()) Text(state.detailError)
                state.detailTransaction?.let { item ->
                    Text("Тип: ${TransactionFilter.kinds[item.kind] ?: item.kind}")
                    Text("Статус детали: ${TransactionFilter.statuses[item.status] ?: item.status}")
                    Text("Версия операции: ${item.version}")
                    item.entries.forEach { entry ->
                        val account = state.accounts.find { it.id == entry.account_id }
                        Text("Счет детали: ${account?.name ?: "Недоступный счет"}${if (account?.archived_at != null) " · В архиве" else ""}")
                        Text("Сумма детали: ${Money.display(entry.amount_minor, entry.currency)}")
                    }
                    Text("Дата детали: ${openingLocal(item.occurred_at, item.occurred_timezone)}")
                    Text("Часовой пояс детали: ${item.occurred_timezone}")
                    Text("Время UTC: ${item.occurred_at}")
                    Text("Примечание детали: ${item.note.ifEmpty { "Нет" }}")
                    Text("Получатель детали: ${item.payee.ifEmpty { "Нет" }}")
                    item.reason?.let { Text("Причина детали: $it") }
                    item.rate?.let { Text("Курс детали: ${it.numerator}/${it.denominator}") }
                    val currency = item.entries.singleOrNull()?.currency
                    if (item.allocations.isNotEmpty() && currency != null) {
                        Text("Распределено: ${Money.display(item.allocations.fold(BigInteger.ZERO) { sum, part -> sum + BigInteger(part.amount_minor) }.toString(), currency)}")
                        item.allocations.forEachIndexed { index, part ->
                            Text("Часть детали ${index + 1}: ${Money.display(part.amount_minor, currency)} · ${categoryPath(part.category_id, state.categories)}")
                            if (item.kind == "expense") Text("Доступный возврат части ${index + 1}: ${Money.display(part.remaining_refundable_minor, currency)}")
                            part.original_allocation_id?.let { originalId ->
                                val parent = state.transactions.find { it.id == item.parent_transaction_id }
                                val original = parent?.allocations?.indexOfFirst { it.id == originalId }
                                if (original != null && original >= 0) Text("Исходная часть возврата: ${original + 1}")
                                else Text("Исходная часть недоступна.")
                            }
                        }
                    }
                    if (item.tag_ids.isEmpty()) Text("Теги детали: Нет")
                    item.tag_ids.forEach { id ->
                        val tag = state.tags.find { it.id == id }
                        Text("Тег детали: ${tag?.name ?: "Недоступный тег"}${if (tag?.archived_at != null) " · В архиве" else ""}")
                    }
                    @Composable fun link(id: String?, label: String) {
                        if (id == null) return
                        val linked = state.transactions.find { it.id == id }
                        TextButton(enabled = !state.busy, onClick = { model.openTransactionDetails(id) }) {
                            Text("$label ${linked?.let(::detailName) ?: "Недоступная запись"}")
                        }
                    }
                    link(item.parent_transaction_id, "Открыть исходную операцию:")
                    link(item.fee_transaction_id, "Открыть комиссию:")
                    val refunds = state.transactions.filter { it.kind == "refund" && it.parent_transaction_id == item.id }
                    if (item.kind == "expense") Text("Возвратов детали: ${refunds.size}")
                    refunds.forEach { link(it.id, "Открыть возврат:") }
                }
            }
        }
    }
}

private fun detailName(item: Transaction) = item.note.ifEmpty { TransactionFilter.kinds[item.kind] ?: item.kind }
