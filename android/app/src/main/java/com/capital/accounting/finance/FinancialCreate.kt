package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.Account
import com.capital.accounting.api.Transaction
import com.capital.accounting.auth.AuthViewModel

enum class FinancialCreation(val prefix: String, val section: String, val label: String) {
    EXPENSE("transaction", "operations", "Создать расход"),
    INCOME("transaction", "operations", "Создать доход"),
    TRANSFER("transfer", "operations", "Создать перевод"),
    REFUND("refund", "operations", "Создать возврат"),
    ADJUSTMENT("adjustment", "operations", "Создать корректировку"),
    ACCOUNT("account", "accounts", "Создать счет"),
    CATEGORY("catalog", "more", "Создать категорию"),
    TAG("catalog", "more", "Создать тег"),
}

data class FinancialCreationRequest(val kind: FinancialCreation, val account: Account? = null, val expense: Transaction? = null, val transfer: Transaction? = null)

enum class FinancialTransactionAction { EDIT, DELETE }
data class FinancialTransactionRequest(val action: FinancialTransactionAction, val transaction: Transaction, val parent: Transaction? = null, val fee: Transaction? = null, val transfer: Transaction? = null) {
    val prefix get() = when (transaction.kind) { "income", "expense" -> "transaction"; else -> transaction.kind }
}

/** An ephemeral UI intent scoped to the signed-in owner, never a submitted command. */
class FinancialCreateRouter {
    var request by mutableStateOf<FinancialCreationRequest?>(null)
    var transactionRequest by mutableStateOf<FinancialTransactionRequest?>(null)
    var canCreate by mutableStateOf(false)
    var editorOpen by mutableStateOf(false)
}

@Composable
fun HandleFinancialTransaction(prefix: String, enabled: Boolean, act: (FinancialTransactionRequest) -> Unit) {
    val router = LocalFinancialCreateRouter.current
    val scope = LocalFinancialDraftScope.current
    val section = LocalFinancialSection.current
    val request = router.transactionRequest
    LaunchedEffect(request, enabled, scope.saving, scope.failed) {
        if (request != null && request.prefix == prefix && enabled && !scope.saving && !scope.failed) {
            router.transactionRequest = null
            section.value = "operations"
            act(request)
        }
    }
}

val LocalFinancialCreateRouter = staticCompositionLocalOf<FinancialCreateRouter> { error("Financial create router required") }

@Composable
fun HandleFinancialCreate(prefix: String, enabled: Boolean, create: (FinancialCreationRequest) -> Unit) {
    val router = LocalFinancialCreateRouter.current
    val scope = LocalFinancialDraftScope.current
    val section = LocalFinancialSection.current
    val request = router.request
    LaunchedEffect(request, enabled, scope.saving, scope.failed) {
        if (request != null && request.kind.prefix == prefix && enabled && !scope.saving && !scope.failed) {
            router.request = null
            section.value = request.kind.section
            create(request)
        }
    }
}

@Composable
fun FinancialAddButton(model: AuthViewModel, router: FinancialCreateRouter) {
    val state = model.state
    var menu by remember(router) { mutableStateOf(false) }
    var choosing by remember(router) { mutableStateOf<FinancialCreation?>(null) }
    val enabled = !state.busy && state.persisted && !state.financeBlocked && router.canCreate && router.request == null && router.transactionRequest == null
    fun dismiss() { menu = false; choosing = null }
    fun select(request: FinancialCreationRequest) { router.request = request; dismiss() }
    LaunchedEffect(state.financeBlocked, state.logoutPending, state.revokePendingId) {
        if (state.financeBlocked || state.logoutPending || state.revokePendingId != null) { router.request = null; router.transactionRequest = null; dismiss() }
    }
    if (!router.editorOpen && state.detailStack.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            Button(enabled = enabled, onClick = { menu = true }) {
                Text("Добавить")
            }
        }
    }
    if (menu) AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(when (choosing) { FinancialCreation.REFUND -> "Исходный расход для возврата"; FinancialCreation.ADJUSTMENT -> "Счет для корректировки"; else -> "Что добавить?" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (choosing) {
                    FinancialCreation.REFUND -> {
                        Button(enabled = !state.busy, onClick = model::loadTransactions) { Text("Загрузить историю для возврата") }
                        val expenses = state.transactions.filter { it.kind == "expense" && it.status == "posted" && (it.parent_transaction_id == null || state.transactions.any { linked -> linked.id == it.parent_transaction_id && linked.kind == "transfer" }) }
                        if (expenses.isEmpty()) Text("Нет исходных расходов. Обновите историю или сначала добавьте расход.")
                        expenses.forEach { expense ->
                            TextButton(enabled = enabled, onClick = { select(FinancialCreationRequest(FinancialCreation.REFUND, expense = expense, transfer = state.transactions.find { it.id == expense.parent_transaction_id })) }) { Text("Возврат для ${expense.note.ifEmpty { "расхода ${expense.entries.joinToString { Money.display(it.amount_minor, it.currency) }} от ${openingLocal(expense.occurred_at, expense.occurred_timezone)}" }}") }
                        }
                    }
                    FinancialCreation.ADJUSTMENT -> {
                        Button(enabled = !state.busy, onClick = model::loadAccounts) { Text("Загрузить счета для корректировки") }
                        val accounts = state.accounts.filter { it.archived_at == null && it.deleted_at == null }
                        if (accounts.isEmpty()) Text("Нет активных счетов. Обновите список или сначала создайте счет.")
                        accounts.forEach { account ->
                            TextButton(enabled = enabled, onClick = { select(FinancialCreationRequest(FinancialCreation.ADJUSTMENT, account = account)) }) { Text("Остаток счета ${account.name} · ${account.currency}") }
                        }
                    }
                    else -> FinancialCreation.entries.forEach { kind ->
                        TextButton(enabled = enabled, onClick = { if (kind in listOf(FinancialCreation.REFUND, FinancialCreation.ADJUSTMENT)) choosing = kind else select(FinancialCreationRequest(kind)) }) { Text(kind.label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::dismiss) { Text("Отменить добавление") } },
        dismissButton = { if (choosing != null) TextButton(onClick = { choosing = null }) { Text("К выбору действия") } },
    )
}
