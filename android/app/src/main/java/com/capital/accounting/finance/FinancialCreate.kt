package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.capital.accounting.api.Account
import com.capital.accounting.api.Transaction
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.auth.AuthState

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
    var operationTarget by mutableStateOf<String?>(null)
    var request by mutableStateOf<FinancialCreationRequest?>(null)
    var transactionRequest by mutableStateOf<FinancialTransactionRequest?>(null)
    var canCreate by mutableStateOf(false)
    var editorOpen by mutableStateOf(false)
    fun routeToFirstAccount(state: AuthState): Boolean {
        if (!state.accountsLoaded || state.accounts.any { it.archived_at == null && it.deleted_at == null }) return false
        request = FinancialCreationRequest(FinancialCreation.ACCOUNT)
        return true
    }
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
    val section = LocalFinancialSection.current.value
    val canQueue = if (section == "accounts") state.canQueueAccounts else state.canQueueBasicCreates
    val enabled = !state.busy && state.persisted && (!state.financeBlocked || canQueue) && router.canCreate && router.request == null && router.transactionRequest == null
    fun select(request: FinancialCreationRequest) { router.request = request }
    LaunchedEffect(state.financeBlocked, canQueue, state.logoutPending, state.revokePendingId) {
        if ((state.financeBlocked && !canQueue) || state.logoutPending || state.revokePendingId != null) { router.request = null; router.transactionRequest = null; router.operationTarget = null }
    }
    if (!router.editorOpen && state.detailStack.isEmpty() && section != "more") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            FloatingActionButton(modifier = Modifier.semantics { contentDescription = if (section == "accounts") "Добавить счет" else "Добавить операцию" }, onClick = {
                if (enabled) {
                    if (section == "accounts") select(FinancialCreationRequest(FinancialCreation.ACCOUNT))
                    else if (!router.routeToFirstAccount(state)) select(FinancialCreationRequest(FinancialCreation.EXPENSE))
                }
            }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }
    }
}
