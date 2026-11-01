package com.capital.accounting.finance

import android.app.AlertDialog
import android.widget.NumberPicker
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun AlertDialog.withCloseHeader(label: String): AlertDialog {
    val density = context.resources.displayMetrics.density
    val row = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((16 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), 0)
    }
    row.addView(android.widget.TextView(context).apply { text = label; textSize = 20f }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(android.widget.Button(context).apply {
        text = "×"; textSize = 24f; contentDescription = "Закрыть окно"
        setOnClickListener { dismiss() }
    }, android.widget.LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
    setCustomTitle(row)
    return this
}

@Composable
fun OperationDateField(value: String, onChange: (String) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val storage = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    val date = runCatching { storage.parse(value) }.getOrNull()
    val display = date?.let { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(it) } ?: value
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(display, {}, readOnly = true, enabled = enabled, label = { Text("Дата операции") }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().semantics { contentDescription = "Выбрать дату операции: $display" }.clickable(enabled = enabled) {
            val calendar = Calendar.getInstance().apply { if (date != null) time = date }
            DatePickerDialog(context, { _, year, month, day ->
                calendar.set(year, month, day)
                TimePickerDialog(context, { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    val seconds = NumberPicker(context).apply {
                        minValue = 0; maxValue = 59; this.value = calendar.get(Calendar.SECOND)
                    }
                    AlertDialog.Builder(context).setTitle("Секунды").setView(seconds)
                        .setPositiveButton("Готово") { _, _ ->
                            calendar.set(Calendar.SECOND, seconds.value)
                            onChange(storage.format(calendar.time))
                        }.setNegativeButton("Отмена", null).create().withCloseHeader("Секунды").show()
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).withCloseHeader("Время операции").show()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).withCloseHeader("Дата операции").show()
        })
    }
}
