package com.capital.accounting.finance

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.util.TimeZone

/** Transient presentation only: raw values and submitted commands keep their existing stores. */
class FieldValidation {
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var focusKey by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    fun submit(values: Map<String, String>): Boolean {
        errors = values
        focusKey = values.keys.firstOrNull()
        revision++
        return values.isEmpty()
    }
    fun changed(key: String) { errors = errors - key }
    fun reset() { errors = emptyMap(); focusKey = null; revision++ }
}

@Composable
fun ValidatedTextField(value: String, onValueChange: (String) -> Unit, label: String, key: String, validation: FieldValidation, enabled: Boolean) {
    val focus = remember { FocusRequester() }
    val location = remember { BringIntoViewRequester() }
    LaunchedEffect(validation.revision) {
        if (validation.focusKey == key && enabled) {
            focus.requestFocus()
            location.bringIntoView()
        }
    }
    OutlinedTextField(value, { onValueChange(it); validation.changed(key) }, label = { Text(label) },
        modifier = Modifier.focusRequester(focus).bringIntoViewRequester(location),
        enabled = enabled, singleLine = true, isError = key in validation.errors,
        supportingText = validation.errors[key]?.let { message -> { Text(message) } })
}

fun nameFieldError(value: String, maximum: Int): String? =
    if (value.isBlank() || value.length > maximum) "Введите название от 1 до $maximum символов." else null

fun accountFieldErrors(name: String, balance: String, opened: String, zone: String, currency: String, creating: Boolean): Map<String, String> {
    val errors = linkedMapOf<String, String>()
    nameFieldError(name, 100)?.let { errors["name"] = it }
    if (creating) {
        try { Money.minor(balance, currency) } catch (e: IllegalArgumentException) { errors["balance"] = e.message ?: "Проверьте начальный остаток." }
        val validZone = zone in TimeZone.getAvailableIDs()
        try { openingInstant(opened, if (validZone) zone else "UTC") }
        catch (_: IllegalArgumentException) { errors["opened"] = "Введите существующую дату в формате YYYY-MM-DDTHH:MM:SS." }
        catch (_: java.text.ParseException) { errors["opened"] = "Введите существующую дату в формате YYYY-MM-DDTHH:MM:SS." }
        if (!validZone) errors["zone"] = "Введите известный часовой пояс, например UTC или Europe/Nicosia."
    }
    return errors
}
