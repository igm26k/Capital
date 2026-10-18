package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import java.util.UUID

@Composable
fun CatalogPanel(model: AuthViewModel) {
    val state = model.state
    val onlineEnabled = !state.busy && !state.financeBlocked && state.persisted
    val enabled = onlineEnabled || state.canQueueCatalog
    val tagEnabled = onlineEnabled || state.canQueueCatalog
    val guard = rememberDraftGuard("catalog", enabled, render = false)
    val scope = LocalFinancialDraftScope.current
    val navigation = LocalFinancialEditor.current
    val editorOpen = navigation.value == "catalog"
    var handledLocalReceipt by remember { mutableStateOf(state.localCollectionReceipt?.actionId) }
    var handledReceipt by remember { mutableStateOf(state.collectionReceipt?.commandId) }
    var collection by rememberFinancialDraft("catalog.collection") { "categories" }
    var id by rememberFinancialDraft<String?>("catalog.id") { null }
    var version by rememberFinancialDraft("catalog.version") { "" }
    var name by rememberFinancialDraft("catalog.name") { "" }
    var parentId by rememberFinancialDraft<String?>("catalog.parentId") { null }
    var archived by rememberFinancialDraft("catalog.archived") { false }
    var error by remember { mutableStateOf("") }
    val validation = remember(scope, scope.epoch) { FieldValidation() }
    fun reset(target: String = "categories") {
        collection = target; id = null; version = ""; name = ""; parentId = null; archived = false; error = ""; validation.reset()
    }
    fun close() {
        if (enabled && !scope.saving) guard.request { reset(); navigation.value = null }
    }
    fun createCatalog(target: String) { guard.request { reset(target); navigation.value = "catalog" } }
    HandleFinancialCreate("catalog", enabled) { createCatalog(if (it.kind == FinancialCreation.TAG) "tags" else "categories") }
    LaunchedEffect(state.financialCommand?.commandId, state.collectionReceipt?.commandId, state.localCollectionReceipt?.actionId) {
        val command = state.financialCommand
        if (command == null) {
            val local = state.localCollectionReceipt
            if (local != null && local.actionId != handledLocalReceipt) {
                handledLocalReceipt = local.actionId
                if (local.collection == collection && (local.recordId == id || local.created && id == null)) {
                    guard.markClean(); navigation.value = null
                }
            }
            val receipt = state.collectionReceipt
            if (receipt?.commandId != handledReceipt) {
                handledReceipt = receipt?.commandId
                if (receipt != null && receipt.collection == collection && (receipt.recordId == id || (receipt.created && id == null))) {
                    if (id != null) version = if (collection == "categories") receipt.result.categories.single { it.id == id }.version else receipt.result.tags.single { it.id == id }.version
                    guard.markClean()
                    if (navigation.value == "catalog") navigation.value = null
                }
            }
            return@LaunchedEffect
        }
        collection = when {
            command.path.contains("/categories") -> "categories"
            command.path.contains("/tags") -> "tags"
            else -> return@LaunchedEffect
        }
        navigation.value = "catalog"
        id = if (command.method == "PUT") command.path.substringAfterLast('/') else null
        if (collection == "categories") {
            if (id == null) {
                val body = decodeResponse<CategoryCreate>(command.body)
                name = body.name; parentId = body.parent_id; archived = false
            } else {
                val body = decodeResponse<CategoryUpdate>(command.body)
                name = body.name; parentId = body.parent_id; archived = body.archived; version = body.expected_version
            }
        } else {
            if (id == null) { name = decodeResponse<TagCreate>(command.body).name; archived = false }
            else {
                val body = decodeResponse<TagUpdate>(command.body)
                name = body.name; archived = body.archived; version = body.expected_version
            }
        }
    }
    if (navigation.value == null && LocalFinancialSection.current.value == "more" && LocalSettingsGroup.current == "classification") Button(enabled = !state.busy, onClick = model::loadCatalog) { Text("Обновить категории и теги") }
    if (!editorOpen && LocalFinancialSection.current.value == "more" && LocalSettingsGroup.current == "classification") {
        Text("Категории и теги", style = MaterialTheme.typography.titleLarge)
        TextButton(enabled = enabled && !scope.saving, onClick = { createCatalog("categories") }) { Text("Новая категория") }
        TextButton(enabled = tagEnabled && !scope.saving, onClick = { createCatalog("tags") }) { Text("Новый тег") }
        TextButton(enabled = enabled && !scope.saving, onClick = { navigation.value = "catalog" }) { Text("Открыть черновик классификации") }
        DraftGuardControls(guard, "catalog", enabled) { navigation.value = "catalog" }
        state.categories.forEach { item ->
            Text("Категория: ${categoryPath(item.id, state.categories)}${if (item.archived_at != null) " · В архиве" else ""}")
            TextButton(enabled = enabled && (state.offline || ("category" to item.id) !in state.localAffected), onClick = { guard.request { collection = "categories"; id = item.id; version = item.version; name = item.name; parentId = item.parent_id; archived = item.archived_at != null; error = ""; validation.reset(); navigation.value = "catalog"  } }) { Text("Изменить категорию ${categoryPath(item.id, state.categories)}") }
        }
        state.tags.forEach { item ->
            Text("Тег: ${item.name}${if (item.archived_at != null) " · В архиве" else ""}")
            TextButton(enabled = tagEnabled && (state.offline || ("tag" to item.id) !in state.localAffected), onClick = { guard.request { collection = "tags"; id = item.id; version = item.version; name = item.name; parentId = null; archived = item.archived_at != null; error = ""; validation.reset(); navigation.value = "catalog"  } }) { Text("Изменить тег ${item.name}") }
        }
    } else if (editorOpen) Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 760.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = enabled && !scope.saving, onClick = ::close) { Text("Отмена") }
                Button(enabled = !state.busy, onClick = model::loadCatalog) { Text("Обновить категории и теги") }
                if (state.busy) CircularProgressIndicator()
                if (state.message.isNotEmpty()) Text(state.message)
                if (scope.failed) {
                    Text("Не удалось сохранить черновики на устройстве. Повторите сохранение.")
                    Button(enabled = !scope.saving, onClick = { scope.persist(retry = true) }) { Text("Повторить локальное сохранение") }
                }
                FinancialCommandStatus(model)
                DraftGuardControls(guard, "catalog", enabled)
                TextButton(enabled = enabled && !scope.saving, onClick = { createCatalog("categories") }) { Text("Новая категория") }
                TextButton(enabled = tagEnabled && !scope.saving, onClick = { createCatalog("tags") }) { Text("Новый тег") }
                val category = collection == "categories"
                Text(if (category) "Редактор категории" else "Редактор тега")
                ValidatedTextField(name, { name = it }, if (category) "Название категории" else "Название тега", "name", validation, enabled)
                if (category) {
                    Text("Родитель: ${if (parentId == null) "Корень" else categoryPath(parentId, state.categories)}")
                    TextButton(enabled = enabled, onClick = { parentId = null }) { Text("Категория в корне") }
                    state.categories.filter { it.id != id && (it.archived_at == null || it.id == parentId) }.forEach { item ->
                        TextButton(enabled = enabled, onClick = { parentId = item.id }) { Text("Родитель категории: ${categoryPath(item.id, state.categories)}") }
                    }
                }
                if (id != null) TextButton(enabled = enabled, onClick = { archived = !archived }) { Text(if (archived) "Восстановить из архива" else "Поместить в архив") }
                Text(if (archived) "Состояние классификации: В архиве" else "Состояние классификации: Активна")
                val currentVersion = if (category) state.categories.find { it.id == id }?.version else state.tags.find { it.id == id }?.version
                if (id != null && currentVersion != null && currentVersion != version) {
                    Text(if (((if (category) "category" else "tag") to id) in state.localAffected) "Ожидаемая версия классификации: $currentVersion; версия черновика: $version" else "Версия классификации на сервере: $currentVersion; версия черновика: $version")
                    Button(enabled = enabled, onClick = { version = currentVersion }) { Text("Использовать обновленную версию классификации") }
                }
                state.categoryConflict?.let { Text("Категория на сервере: ${it.name}; версия ${it.version}") }
                state.tagConflict?.let { Text("Тег на сервере: ${it.name}; версия ${it.version}") }
                if (error.isNotEmpty()) Text(error)
                if (state.offline) Text("Категории и теги сохраняются локально; для отправки потребуется действующая сессия на сервере.")
                Button(enabled = enabled, onClick = save@{
                    error = ""
                    val issue = nameFieldError(name, if (category) 100 else 50)
                    if (!validation.submit(if (issue == null) emptyMap() else mapOf("name" to issue))) return@save
                    try {
                        require(name.isNotBlank() && name.length <= if (category) 100 else 50)
                        val body = if (category) {
                            if (id == null) ApiClient.json.encodeToString(CategoryCreate(UUID.randomUUID().toString(), name, parentId))
                            else ApiClient.json.encodeToString(CategoryUpdate(version, name, archived, parentId))
                        } else {
                            if (id == null) ApiClient.json.encodeToString(TagCreate(UUID.randomUUID().toString(), name))
                            else ApiClient.json.encodeToString(TagUpdate(version, name, archived))
                        }
                        error = ""; model.submitCatalog(body, collection, id)
                    } catch (_: Exception) { error = "Проверьте название классификации и его длину." }
                }) { Text(if (category) "Сохранить категорию" else "Сохранить тег") }

            }
        }
    }
}
