package com.capital.accounting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.finance.AccountPanel
import com.capital.accounting.finance.AdjustmentPanel
import com.capital.accounting.finance.RefundPanel
import com.capital.accounting.finance.TransferPanel
import com.capital.accounting.finance.TransactionPanel
import com.capital.accounting.finance.CatalogPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AuthViewModel = viewModel()
            val state = model.state
            var origin by remember(state.origin) { mutableStateOf(state.origin) }
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var device by remember { mutableStateOf("Android") }
            LaunchedEffect(state.auth) { if (state.auth != null) password = "" }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Capital", style = MaterialTheme.typography.headlineLarge)
                        Text("Ручной учет финансов")
                        if (state.message.isNotEmpty()) Text(state.message)
                        if (state.busy) CircularProgressIndicator()
                        if (state.revokePendingId != null) {
                            Text("Устройство: ${state.revokePendingId}")
                            Button(enabled = !state.busy, onClick = { model.revokeSession(state.revokePendingId) }) { Text("Повторить отзыв устройства") }
                        } else if (state.logoutPending) {
                            Button(enabled = !state.busy, onClick = model::logout) { Text("Повторить выход") }
                        } else if (state.auth != null) {
                            Text("Вы вошли: ${state.auth.profile.email}")
                            Text("Пространство: ${state.auth.workspace.name}")
                            Text("Устройство: ${state.auth.session.device_name}")
                            Text("Сессия действует до: ${state.auth.session.expires_at}")
                            if (!state.persisted) Button(enabled = !state.busy, onClick = model::persist) { Text("Повторить сохранение сессии") }
                            Button(enabled = !state.busy && !state.financeBlocked, onClick = model::renew) { Text("Продлить сессию") }
                            Button(enabled = !state.busy && !state.financeBlocked, onClick = model::logout) { Text("Выйти") }
                            Text("Устройства", style = MaterialTheme.typography.titleLarge)
                            Button(enabled = !state.busy && state.persisted, onClick = model::loadSessions) { Text("Обновить устройства") }
                            state.sessions.forEach { session ->
                                Text(session.device_name)
                                if (session.is_current) Text("Текущее устройство")
                                Text("Последняя активность: ${session.last_seen_at}")
                                Button(enabled = !state.busy && state.persisted && !state.financeBlocked, onClick = { model.revokeSession(session.id) }) {
                                    Text(if (session.is_current) "Завершить текущую сессию" else "Отключить устройство ${session.device_name}")
                                }
                            }
                            AccountPanel(model)
                            CatalogPanel(model)
                            TransactionPanel(model)
                            TransferPanel(model)
                            RefundPanel(model)
                            AdjustmentPanel(model)
                        } else {
                            OutlinedTextField(origin, { origin = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                            Button(enabled = !state.busy, onClick = { model.saveOrigin(origin) }) { Text("Сохранить адрес") }
                            if (state.restorePending) Button(enabled = !state.busy, onClick = model::restore) { Text("Проверить сохраненную сессию") }
                            if (state.origin.isNotEmpty()) {
                                Text("Сервер: ${state.origin}")
                                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                                OutlinedTextField(device, { device = it }, label = { Text("Название устройства") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                                Button(enabled = !state.busy && origin == state.origin && email.isNotBlank() && password.isNotEmpty() && device.isNotBlank(), onClick = { model.authenticate(email, password, device, false); password = "" }) { Text("Войти") }
                                Button(enabled = !state.busy && origin == state.origin && email.isNotBlank() && password.isNotEmpty() && device.isNotBlank(), onClick = { model.authenticate(email, password, device, true); password = "" }) { Text("Зарегистрироваться") }
                            }
                        }
                    }
                }
            }
        }
    }
}
