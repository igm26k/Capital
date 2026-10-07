package com.capital.accounting.finance

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.capital.accounting.auth.AuthViewModel

@Composable
fun CatalogPanel(model: AuthViewModel) {
    val state = model.state
    Text("Категории и теги", style = MaterialTheme.typography.titleLarge)
    Button(enabled = !state.busy, onClick = model::loadCatalog) { Text("Обновить категории и теги") }
    state.categories.forEach { item ->
        val parent = state.categories.find { it.id == item.parent_id }
        Text("Категория: ${if (parent == null) "" else parent.name + " / "}${item.name}${if (item.archived_at != null) " · В архиве" else ""}")
    }
    state.tags.forEach { item -> Text("Тег: ${item.name}${if (item.archived_at != null) " · В архиве" else ""}") }
}
