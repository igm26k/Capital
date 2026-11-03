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
import com.capital.accounting.auth.AuthViewModel

@Composable
fun FinancialEditorScreen(model: AuthViewModel, prefix: String, guard: DraftGuard, backLabel: String, onClose: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val state = model.state
    val scope = LocalFinancialDraftScope.current
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
            ModalScrollContent(when (prefix) { "transfer" -> "Перевод"; "refund" -> "Возврат"; "adjustment" -> "Корректировка"; else -> backLabel }, onClose, enabled && !scope.saving, footer = footer) {

                if (state.message.isNotEmpty()) Text(state.message)
                if (scope.failed) {
                    Text("Не удалось сохранить черновики на устройстве. Повторите сохранение.")
                    Button(enabled = !scope.saving, onClick = { scope.persist(retry = true) }) { Text("Повторить локальное сохранение") }
                }
                FinancialCommandStatus(model)
                DraftGuardControls(guard, prefix, enabled)
                content()
            }
        }
    }
}
