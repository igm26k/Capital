package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.capital.accounting.auth.AuthViewModel

@Composable
fun FinancialCommandStatus(model: AuthViewModel, accountDraft: String? = null) {
    val state = model.state
    state.financialCommand?.let { command ->
        Text("Сохраненная команда: ${command.commandId}")
        Text("Состояние: ${command.state}")
        if (state.offline) Button(enabled = !state.busy, onClick = model::loadOverview) { Text("Проверить сохраненную сессию") }
        command.requestId?.let { Text("Идентификатор обращения: $it") }
        if (command.state == "pending" && command.sessionId != state.auth?.session?.id && command.generationId == state.auth?.workspace?.sync_generation_id) {
            Button(enabled = !state.busy && !state.offline, onClick = model::rebindAccountCommand) { Text("Продолжить сохраненную команду в этой сессии") }
        }
        if (command.state == "pending") Button(enabled = !state.busy && !state.offline && command.sessionId == state.auth?.session?.id && command.generationId == state.auth?.workspace?.sync_generation_id, onClick = model::retryAccountCommand) { Text("Повторить сохраненную команду") }
        if (command.state == "confirmed" || command.state == "rejected") Button(enabled = !state.busy && !state.offline, onClick = model::acceptAccountResult) { Text(if (command.state == "confirmed") "Принять подтвержденный результат" else "Обновить данные для новой команды") }
        if (accountDraft != null) state.accountConflict?.let { current ->
            Text("На сервере: ${current.name}, ${current.type}, версия ${current.version}; ${if (current.archived_at != null) "в архиве" else "активен"}")
            Text(accountDraft)
        }
    }
}
