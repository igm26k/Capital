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
fun ModalScrollContent(title: String, onClose: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.imePadding()) {
        Box(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
            ModalHeader({ Text(title, style = MaterialTheme.typography.titleLarge) }, onClose, enabled)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun CapitalAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton,
        modifier = modifier, dismissButton = dismissButton, icon = icon, text = text,
        title = { ModalHeader({ title?.invoke() }, onDismissRequest) })
}
