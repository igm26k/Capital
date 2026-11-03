package com.capital.accounting

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.capital.accounting.finance.*
import androidx.compose.ui.graphics.Color
import com.capital.accounting.finance.LocalFinancialSection
import com.capital.accounting.finance.LocalFinancialCreateRouter
import com.capital.accounting.finance.FinancialCreateRouter
import com.capital.accounting.finance.FinancialAddButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capital.accounting.auth.AuthViewModel
import com.capital.accounting.finance.FinancialDraftContent
import com.capital.accounting.finance.QueueRecovery
import com.capital.accounting.finance.TransactionDetails
import com.capital.accounting.finance.AccountPanel
import com.capital.accounting.finance.AdjustmentPanel
import com.capital.accounting.finance.RefundPanel
import com.capital.accounting.finance.TransferPanel
import com.capital.accounting.finance.TransactionPanel
import com.capital.accounting.finance.PrivacyPanel
import com.capital.accounting.finance.CsvPanel
import com.capital.accounting.finance.AnalyticsPanel
import com.capital.accounting.finance.CatalogPanel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AuthViewModel = viewModel()
            val state = model.state
            val networkRequests by com.capital.accounting.api.ApiClient.activeRequests.collectAsState()
            val analytics: AnalyticsModel = viewModel()
            val csv: CsvModel = viewModel()
            var origin by remember(state.origin) { mutableStateOf(state.origin) }
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            var emailCode by remember { mutableStateOf("") }
            var device by remember { mutableStateOf("Android") }
            val navigation = rememberSaveable(state.origin, state.auth?.profile?.id, state.auth?.workspace?.id, saver = AppNavigation.Saver) { AppNavigation() }
            val section = navigation.section
            val createRouter = remember(state.origin, state.auth?.profile?.id, state.auth?.workspace?.id) { FinancialCreateRouter() }
            LaunchedEffect(state.auth) { if (state.auth != null) password = "" }
            LaunchedEffect(createRouter.request, createRouter.editorPrefix) {
                if (createRouter.request?.kind == FinancialCreation.ACCOUNT || createRouter.editorPrefix == "account") navigation.settingsGroup = "accounts"
            }
            val scroll = rememberScrollState()
            val settingsGroup = navigation.settingsGroup
            val settingsState = rememberSaveableStateHolder()
            val preferences = remember { getSharedPreferences("capital.appearance", MODE_PRIVATE) }
            var appearance by remember { mutableStateOf(preferences.getString("theme", "system") ?: "system") }
            val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
            LaunchedEffect(section.value) { if (section.value != "more") navigation.settingsGroup = "home" }
            // Dialogs handle Back in their own windows before this root handler.
            BackHandler {
                if (state.auth != null) { if (section.value == "more" && settingsGroup != "home") navigation.settingsGroup = "home" else navigation.back() }
            }
            MaterialTheme(colorScheme = capitalColors(dark), typography = capitalTypography) {
                CompositionLocalProvider(LocalFinancialSection provides section, LocalFinancialCreateRouter provides createRouter, LocalSettingsGroup provides settingsGroup, LocalFinancialScrollState provides scroll) {
                    if (state.auth != null && state.recoveryOpen) QueueRecovery(model)
                    if (state.auth != null && state.detailStack.isNotEmpty()) TransactionDetails(model)
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                            if (state.auth == null) Surface(color = if (isSystemInDarkTheme()) Color(0xff18263f) else Color(0xff1f3155), contentColor = Color.White) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(Modifier.weight(1f), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        if (state.auth != null && section.value != "accounts") IconButton(onClick = navigation::back, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Назад" }) { CapitalIcon("back") }
                                        Image(painterResource(R.drawable.capital_logo), contentDescription = null, modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Capital", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    if (state.auth != null) TextButton(enabled = !state.busy && (!state.financeBlocked || (state.offline && state.financialCommand == null && state.localPending == 0 && state.localBlocked == 0)), onClick = model::logout, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Выход") }
                                }
                            }
                            LaunchedEffect(section.value, settingsGroup) { scroll.scrollTo(0) }
                            Column(Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding().verticalScroll(scroll).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.message.isNotEmpty()) Text(state.message)

                                if (state.revokePendingId != null) {
                                    Text("Устройство: ${state.revokePendingId}")
                                    Button(enabled = !state.busy, onClick = { model.revokeSession(state.revokePendingId) }) { Text("Повторить отзыв устройства") }
                                } else if (state.logoutPending) {
                                    Button(enabled = !state.busy, onClick = model::logout) { Text("Повторить выход") }
                                } else if (state.auth != null) {
                                    if (state.offline) {
                                        Text("Нет соединения. Показаны сохраненные данные; они могут быть устаревшими. Доходы, расходы, переводы, возвраты и корректировки остатков, изменения счетов, категорий и тегов сохраняются на устройстве.")
                                        Button(enabled = !state.busy, onClick = model::loadOverview) { Text("Проверить соединение") }
                                    }
                                    if (state.localPending > 0) Text("Локальные изменения ожидают синхронизации: ${state.localPending}.")
                                    if (state.localBlocked > 0) Text("Сохраненные изменения требуют проверки: ${state.localBlocked}.")
                                    if (state.queueMessage.isNotEmpty()) Text(state.queueMessage)
                                    if (state.localPending + state.localBlocked > 0) Button(enabled = !state.busy && !state.queueRunning && state.persisted && state.financialCommand == null,
                                        onClick = model::synchronizeQueue) { Text("Синхронизировать изменения") }
                                    if (state.localPending + state.localBlocked > 0) Button(enabled = !state.busy && state.persisted,
                                        onClick = model::openQueueRecovery) { Text("Разобрать сохранённые изменения") }
                                    if (state.busy && !state.overviewLoaded) Text("Загрузка финансовых данных…")
                                    if (state.busy && state.overviewCached) Text("Показаны сохраненные финансовые данные. Выполняется синхронизация.")
                                    if (state.overviewError.isNotEmpty()) {
                                        Text(state.overviewError)
                                        Button(enabled = !state.busy && state.persisted, onClick = model::loadOverview) { Text("Повторить загрузку финансовых данных") }
                                    }
                                    if (!state.persisted) Button(enabled = !state.busy, onClick = model::persist) { Text("Повторить сохранение сессии") }
                                    if (section.value == "more") {
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            if (settingsGroup != "home") IconButton(onClick = { navigation.settingsGroup = "home" }) { CapitalIcon("back") }
                                            Text(when(settingsGroup) { "sources" -> "Банки и уведомления"; "profile" -> "Профиль"; "security" -> "Безопасность и сессии"; else -> "Настройки" }, style = MaterialTheme.typography.titleLarge)
                                        }
                                        if (settingsGroup == "home") {
                                            Card(onClick = { navigation.settingsGroup = "profile" }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text("Профиль", style = MaterialTheme.typography.titleMedium)
                                                    Text(state.auth.profile.email, style = MaterialTheme.typography.bodyMedium)
                                                    HorizontalDivider()
                                                    Text("Пространство · ${state.auth.workspace.name}", style = MaterialTheme.typography.bodySmall)
                                                    Text("Часовой пояс · ${state.auth.profile.timezone}", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            listOf(
                                                "Управление финансами" to listOf(Triple("accounts", "Счета", "card"), Triple("classification", "Категории и теги", "category"), Triple("analytics", "Бюджеты, отчёты и правила", "records")),
                                                "Интеграции и данные" to listOf(Triple("sources", "Банки и уведомления", "bank"), Triple("data", "Импорт и экспорт", "transfer")),
                                                "Приложение" to listOf(Triple("profile", "Профиль и оформление", "settings"), Triple("security", "Безопасность и сессии", "settings"))
                                            ).forEach { (title, rows) ->
                                                Spacer(Modifier.height(8.dp)); Text(title, style = MaterialTheme.typography.titleSmall)
                                                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 12.dp)) {
                                                    rows.forEachIndexed { index, (id, label, icon) ->
                                                        if (index > 0) HorizontalDivider()
                                                        TextButton(onClick = { navigation.settingsGroup = id }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                                                            CapitalIcon(icon); Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface); Text("›")
                                                        }
                                                    }
                                                } }
                                            }
                                            TextButton(enabled = !state.busy && !state.financeBlocked, onClick = model::renew) { Text("Продлить сессию") }
                                            TextButton(enabled = !state.busy && (!state.financeBlocked || (state.offline && state.financialCommand == null && state.localPending == 0 && state.localBlocked == 0)), onClick = model::logout, modifier = Modifier.fillMaxWidth()) { Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error) }
                                        }
                                        when (settingsGroup) {
                                            "profile" -> {
                                                Text("Профиль", style = MaterialTheme.typography.titleMedium)
                                                CapitalSelect("Тема оформления", appearance, listOf("system" to "Системная", "dark" to "Тёмная", "light" to "Светлая")) { appearance = it; preferences.edit().putString("theme", it).apply() }
                                                Text(state.auth.profile.email)
                                                Text("Пространство: ${state.auth.workspace.name}")
                                                Text("Часовой пояс: ${state.auth.profile.timezone}")
                                                TextButton(enabled = !state.busy && !state.financeBlocked, onClick = model::renew) { Text("Продлить сессию") }
                                            }
                                            "security" -> {
                                                PrivacyPanel(model)
                                                Text("Устройства", style = MaterialTheme.typography.titleMedium)
                                                TextButton(enabled = !state.busy && state.persisted, onClick = model::loadSessions) { Text("Обновить устройства") }
                                                state.sessions.forEach { session ->
                                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                                        Column(Modifier.padding(12.dp)) {
                                                            Text(session.device_name)
                                                            if (session.is_current) Text("Текущее устройство")
                                                            Text("Последняя активность: ${session.last_seen_at}")
                                                            TextButton(enabled = !state.busy && state.persisted && !state.financeBlocked, onClick = { model.revokeSession(session.id) }) { Text("Отключить устройство") }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        settingsState.SaveableStateProvider("${state.origin}:${state.auth.profile.id}:${state.auth.workspace.id}:$settingsGroup") {
                                            if (settingsGroup == "analytics") AnalyticsPanel(model)
                                            if (settingsGroup == "data") CsvPanel(model)
                                            if (settingsGroup == "sources") com.capital.accounting.imports.NotificationPanel(model)
                                        }
                                    }
                                    key(state.origin, state.auth.profile.id, state.auth.workspace.id) {
                                        FinancialDraftContent(this@MainActivity, state.origin, state.auth.profile.id, state.auth.workspace.id) {
                                            AccountPanel(model)
                                            CatalogPanel(model)
                                            TransactionPanel(model)
                                            TransferPanel(model)
                                            RefundPanel(model)
                                            AdjustmentPanel(model)
                                        }
                                    }
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
                                        Button(enabled = !state.busy && origin == state.origin && email.isNotBlank(), onClick = { model.requestPasswordRecovery(email); password = ""; emailCode = "" }) { Text("Забыли пароль?") }
                                        if (state.emailStep.isNotEmpty()) {
                                            OutlinedTextField(emailCode, { emailCode = it }, label = { Text("Код из письма") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                                            Button(enabled = !state.busy && origin == state.origin && emailCode.length == 43 && password.length >= 12 && device.isNotBlank(), onClick = { model.confirmEmail(email, emailCode, password, device); password = ""; emailCode = "" }) { Text(if (state.emailStep == "registration") "Подтвердить регистрацию" else "Сохранить новый пароль") }
                                        }
                                    }
                                }
                            }
                            if (state.auth != null && !state.logoutPending && state.revokePendingId == null) {
                                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                                    listOf(Triple("accounts", "Счета", "◫"), Triple("operations", "Операции", "⇄"), Triple("more", "Настройки", "⋯")).forEach { (id, label, symbol) ->
                                        NavigationBarItem(selected = section.value == id, onClick = { section.value = id }, icon = { CapitalIcon(if (id == "accounts") "card" else if (id == "operations") "transfer" else "settings") }, label = { Text(label) }, modifier = Modifier.semantics { contentDescription = "Раздел $label" })
                                    }
                                }
                            }
                        }
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).navigationBarsPadding().padding(bottom = 104.dp)) {
                            if (state.auth != null && !state.logoutPending && state.revokePendingId == null) FinancialAddButton(model, createRouter)
                        }
                        }
                    }
                    if (state.busy || state.queueRunning || networkRequests > 0 || analytics.busy || csv.busy || createRouter.loadingDrafts) {
                        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Загрузка" })
                            }
                        }
                    }
                }
            }
        }
    }
}
