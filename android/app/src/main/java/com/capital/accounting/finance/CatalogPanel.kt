package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.capital.accounting.api.*
import com.capital.accounting.auth.AuthViewModel
import kotlinx.serialization.encodeToString
import java.util.UUID

@Composable
fun CatalogPanel(model: AuthViewModel) {
    val state = model.state
    val enabled = !state.busy && !state.financeBlocked && state.persisted
    var collection by remember { mutableStateOf("categories") }
    var id by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(state.financialCommand?.commandId) {
        val command = state.financialCommand ?: return@LaunchedEffect
        collection = when {
            command.path.contains("/categories") -> "categories"
            command.path.contains("/tags") -> "tags"
            else -> return@LaunchedEffect
        }
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
    Text("Категории и теги", style = MaterialTheme.typography.titleLarge)
    Button(enabled = !state.busy, onClick = model::loadCatalog) { Text("Обновить категории и теги") }
    TextButton(enabled = enabled, onClick = { collection = "categories"; id = null; name = ""; parentId = null; archived = false; error = "" }) { Text("Новая категория") }
    TextButton(enabled = enabled, onClick = { collection = "tags"; id = null; name = ""; parentId = null; archived = false; error = "" }) { Text("Новый тег") }
    val category = collection == "categories"
    Text(if (category) "Редактор категории" else "Редактор тега")
    OutlinedTextField(name, { name = it }, label = { Text(if (category) "Название категории" else "Название тега") }, enabled = enabled, singleLine = true)
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
        Text("Версия классификации на сервере: $currentVersion; версия черновика: $version")
        Button(enabled = enabled, onClick = { version = currentVersion }) { Text("Использовать обновленную версию классификации") }
    }
    state.categoryConflict?.let { Text("Категория на сервере: ${it.name}; версия ${it.version}") }
    state.tagConflict?.let { Text("Тег на сервере: ${it.name}; версия ${it.version}") }
    if (error.isNotEmpty()) Text(error)
    Button(enabled = enabled, onClick = {
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
    state.categories.forEach { item ->
        Text("Категория: ${categoryPath(item.id, state.categories)}${if (item.archived_at != null) " · В архиве" else ""}")
        TextButton(enabled = enabled, onClick = { collection = "categories"; id = item.id; version = item.version; name = item.name; parentId = item.parent_id; archived = item.archived_at != null; error = "" }) { Text("Изменить категорию ${categoryPath(item.id, state.categories)}") }
    }
    state.tags.forEach { item ->
        Text("Тег: ${item.name}${if (item.archived_at != null) " · В архиве" else ""}")
        TextButton(enabled = enabled, onClick = { collection = "tags"; id = item.id; version = item.version; name = item.name; archived = item.archived_at != null; error = "" }) { Text("Изменить тег ${item.name}") }
    }
}
