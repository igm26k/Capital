package com.capital.accounting.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ModalHeader(title: @Composable () -> Unit, onClose: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { title() }
        IconButton(enabled = enabled, onClick = onClose, modifier = Modifier.size(48.dp).semantics { contentDescription = "Закрыть окно" }) { CapitalIcon("close") }
    }
}

@Composable
fun ModalScrollContent(title: String, onClose: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column((if (footer != null) modifier.fillMaxSize() else modifier).imePadding()) {
        Box(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
            if (footer != null) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = enabled, onClick = onClose, modifier = Modifier.semantics { contentDescription = "Закрыть окно" }) { CapitalIcon("back") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            } else ModalHeader({ Text(title, style = MaterialTheme.typography.titleLarge) }, onClose, enabled)
        }
        Column(Modifier.weight(1f, fill = footer != null).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        if (footer != null) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { footer() }
    }
}

@Composable
fun CapitalAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, icon = icon, text = text,
        title = { ModalHeader({ title?.invoke() }, onDismissRequest) })
}
