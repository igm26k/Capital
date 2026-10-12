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
fun ValidatedTextField(value: String, onValueChange: (String) -> Unit, label: String, key: String, validation: FieldValidation, enabled: Boolean, singleLine: Boolean = true) {
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
        enabled = enabled, singleLine = singleLine, isError = key in validation.errors,
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


/** Keys follow the editor's visual order; split fields use durable allocation IDs. */
fun transactionFieldErrors(amount: String, occurred: String, zone: String, payee: String, note: String, currency: String, parts: List<AllocationDraft>): Map<String, String> {
    val errors = linkedMapOf<String, String>()
    fun positive(raw: String, key: String): String? = try {
        Money.minor(raw, currency).also {
            if (java.math.BigInteger(it).signum() <= 0) errors[key] = "Сумма должна быть больше нуля."
        }
    } catch (e: IllegalArgumentException) { errors[key] = e.message ?: "Проверьте сумму."; null }
    val total = positive(amount, "amount")
    val validZone = zone in TimeZone.getAvailableIDs()
    try { openingInstant(occurred, if (validZone) zone else "UTC") }
    catch (_: IllegalArgumentException) { errors["occurred"] = "Введите существующую дату в формате YYYY-MM-DDTHH:MM:SS." }
    catch (_: java.text.ParseException) { errors["occurred"] = "Введите существующую дату в формате YYYY-MM-DDTHH:MM:SS." }
    if (!validZone) errors["zone"] = "Введите известный часовой пояс, например UTC или Europe/Nicosia."
    if (payee.length > 200) errors["payee"] = "Получатель должен содержать не более 200 символов."
    if (note.length > 2000) errors["note"] = "Примечание должно содержать не более 2000 символов."
    if (parts.size > 1) {
        val minors = parts.map { positive(it.amount, "part.${it.id}") }
        if (total != null && minors.all { it != null } && minors.fold(java.math.BigInteger.ZERO) { sum, value -> sum + java.math.BigInteger(value!!) } != java.math.BigInteger(total)) {
            parts.forEach { if ("part.${it.id}" !in errors) errors["part.${it.id}"] = "Сумма частей должна точно совпадать с суммой операции." }
        }
    }
    return errors
}


fun transferFieldErrors(sourceAmount: String, targetAmount: String, sourceCurrency: String, targetCurrency: String,
    occurred: String, zone: String, payee: String, note: String, feeEnabled: Boolean,
    feeCurrency: String, feeAmount: String, feeNote: String, feeParts: List<AllocationDraft>): Map<String, String> {
    val base = transactionFieldErrors(sourceAmount, occurred, zone, payee, note, sourceCurrency, listOf(AllocationDraft(id = "validation")))
    val errors = linkedMapOf<String, String>()
    base["amount"]?.let { errors["sourceAmount"] = it }
    if (sourceCurrency != targetCurrency) {
        val target = transactionFieldErrors(targetAmount, occurred, zone, "", "", targetCurrency, listOf(AllocationDraft(id = "validation")))
        target["amount"]?.let { errors["targetAmount"] = it }
    }
    base.filterKeys { it != "amount" }.forEach { (key, message) -> errors[key] = message }
    if (feeEnabled) {
        val fee = transactionFieldErrors(feeAmount, occurred, zone, "", feeNote, feeCurrency, feeParts)
        fee["amount"]?.let { errors["feeAmount"] = it }
        fee["note"]?.let { errors["feeNote"] = it }
        fee.filterKeys { it.startsWith("part.") }.forEach { (key, message) -> errors["fee.$key"] = message }
    }
    return errors
}
