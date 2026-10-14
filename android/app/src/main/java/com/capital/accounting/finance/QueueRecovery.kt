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
import com.capital.accounting.api.TransactionFilter
import com.capital.accounting.api.ApiClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun QueueRecovery(model: AuthViewModel) {
    val state = model.state
    Dialog(onDismissRequest = model::closeQueueRecovery, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Проверка сохранённых изменений", style = MaterialTheme.typography.headlineLarge)
                Button(enabled = !state.busy, onClick = model::closeQueueRecovery) { Text("Закрыть проверку очереди") }
                if (state.busy) CircularProgressIndicator()
                if (state.recoveryError.isNotEmpty()) Text(state.recoveryError)
                val head = state.recoveryCommands.firstOrNull()
                if (head == null) Text("Нет изменений, ожидающих отправки.")
                else {
                    Text("Команд ожидает обработки: ${state.recoveryCommands.size}")
                    val suffix = head.path.substringAfter("/workspaces/${head.workspace}/")
                    val resource = suffix.substringBefore('/')
                    val input = ApiClient.json.parseToJsonElement(head.body).jsonObject
                    val adjustment = suffix.endsWith("/adjustments")
                    val classification = suffix == "transactions/classification"
                    val kind = when {
                        adjustment -> "Корректировка остатка"
                        classification -> "Массовая классификация"
                        resource == "accounts" -> "Счёт"
                        resource == "categories" -> "Категория"
                        resource == "tags" -> "Тег"
                        else -> TransactionFilter.kinds[input["kind"]?.jsonPrimitive?.content] ?: "Операция"
                    }
                    val recordId = if (adjustment) suffix.split('/')[1] else input["id"]?.jsonPrimitive?.content ?: head.path.substringAfterLast('/')
                    val proposed = input["name"]?.jsonPrimitive?.content ?: input["note"]?.jsonPrimitive?.content
                    val currentName = when (resource) {
                        "accounts" -> state.accounts.find { it.id == recordId }?.name
                        "categories" -> state.categories.find { it.id == recordId }?.name
                        "tags" -> state.tags.find { it.id == recordId }?.name
                        else -> state.transactions.find { it.id == recordId }?.note
                    }
                    val currentVersion = when (resource) {
                        "accounts" -> state.accounts.find { it.id == recordId }?.version
                        "categories" -> state.categories.find { it.id == recordId }?.version
                        "tags" -> state.tags.find { it.id == recordId }?.version
                        else -> state.transactions.find { it.id == recordId }?.version
                    }
                    Text("Изменение: $kind · ${when { classification -> "Изменение"; head.method == "POST" -> "Создание"; head.method == "DELETE" -> "Удаление"; else -> "Редактирование" }}")
                    if (classification) Text("Операций в пакете: ${input["items"]?.jsonArray?.size ?: 0}")
                    if (adjustment) state.accounts.find { it.id == recordId }?.let { account ->
                        input["target_balance_minor"]?.jsonPrimitive?.content?.let { Text("Предложенный остаток: ${Money.display(it, account.currency)}") }
                        Text("Текущий остаток: ${Money.display(account.posted_balance_minor, account.currency)}")
                        Text("Наблюдаемая версия остатка: ${input["expected_balance_version"]?.jsonPrimitive?.content}")
                        Text("Версия остатка в показанных данных: ${account.balance_version}")
                    }
                    proposed?.let { Text("Предложено: $it") }
                    currentName?.let { Text("Текущее значение: $it") }
                    input["expected_version"]?.jsonPrimitive?.content?.let { Text("Наблюдаемая версия: $it") }
                    currentVersion?.let { Text("Версия в показанных данных: $it") }
                    Text("Состояние: ${when (head.state) { "queued" -> "Не отправлена"; "rejected" -> "Отклонена"; "reconcile" -> "Требует сверки"; else -> "Результат неизвестен" }}")
                    state.recoveryFailure?.let { error ->
                        Text("Ошибка: ${error.code ?: "Неопределённый результат"}")
                        error.requestId?.let { Text("Идентификатор обращения: $it") }
                    }
                    Text("После исключения новая команда не создаётся. Для исправления откройте актуальные данные и заполните изменение заново.")
                    Button(enabled = !state.busy && !state.offline && head.state in setOf("sending", "uncertain", "reconcile", "rejected"), onClick = model::inspectQueueHead) { Text("Сверить результат с сервером") }
                    Button(enabled = !state.busy && !state.queueRunning && state.financialCommand == null && head.state in setOf("queued", "rejected"), onClick = model::previewQueueDismissal) { Text("Подготовить исключение команды") }
                }
                state.recoveryPlan?.let { plan ->
                    Text("Исключить команд из отправки: ${plan.commands.size}")
                    Text("Включены все зависимые неотправленные изменения. Исходные тела, ключи и ошибки останутся на устройстве.")
                    Button(enabled = !state.busy, onClick = model::dismissQueueBranch) { Text("Подтвердить исключение из отправки") }
                    TextButton(enabled = !state.busy, onClick = model::cancelQueueDismissal) { Text("Отменить исключение") }
                }
            }
        }
    }
}
