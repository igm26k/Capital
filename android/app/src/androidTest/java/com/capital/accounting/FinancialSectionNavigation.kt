package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** API scenarios use the same visible section controls as a person using the app. */
fun ComposeContentTestRule.prepareFinancialSection(text: String) {
    if (onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) return
    val editorLabels = listOf("Вернуться к счетам", "Вернуться к категориям и тегам", "Вернуться к истории операций", "Вернуться к истории переводов", "Вернуться к истории возвратов", "Вернуться к истории корректировок")
    if (editorLabels.any { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }) return
    val section = when {
        text.matches(Regex("−?[0-9][0-9 ,]* [A-Z]{3}")) || (text.endsWith(" · В архиве") && !text.startsWith("Категория: ") && !text.startsWith("Тег: ")) -> "Счета"
        text in setOf("Добавить счет", "Открыть черновик счета", "Активные счета", "Архивные счета", "Все счета") || listOf("Изменить счет ", "Восстановить черновик счета ", "Отбросить сохраненный черновик счета ").any { text.startsWith(it) } -> "Счета"
        text in setOf("Новая категория", "Новый тег", "Открыть черновик классификации", "Обновить устройства", "Завершить текущую сессию") || listOf("Категория: ", "Тег: ", "Изменить категорию ", "Изменить тег ", "Восстановить черновик классификации ", "Отбросить сохраненный черновик классификации ", "Отключить устройство ").any { text.startsWith(it) } -> "Еще"
        text in setOf("Новая операция", "Новый перевод", "История операций", "Фильтры истории", "Показать по фильтрам", "Сбросить фильтры истории", "Расход", "Доход", "Открыть черновик операции", "Открыть черновик перевода", "Открыть черновик возврата", "Открыть черновик корректировки", "Сумма операции", "Сумма списания перевода", "Примечание возврата", "Фактический остаток") || listOf("Расход · ", "Доход · ", "Начальный остаток · ", "Перевод · ", "Возврат · ", "Корректировка · ", "По фильтрам операций нет.", "Фильтры истории применены", "Все операции по страницам", "Для операции: ", "Списать со счета: ", "Создать возврат ", "Корректировать счет ", "Изменить операцию ", "Изменить перевод ", "Изменить возврат ", "Изменить корректировку ", "Удалить операцию ", "Удалить перевод ", "Удалить возврат ", "Удалить корректировку ", "Детали операции ", "Восстановить черновик операции ", "Восстановить черновик перевода ", "Восстановить черновик возврата ", "Восстановить черновик корректировки ").any { text.startsWith(it) } -> "Операции"
        else -> return
    }
    val navigation = onAllNodesWithContentDescription("Раздел $section")
    if (navigation.fetchSemanticsNodes().isNotEmpty()) onNodeWithContentDescription("Раздел $section").performClick()
}

fun ComposeContentTestRule.financialNode(text: String): SemanticsNodeInteraction {
    prepareFinancialSection(text)
    return onNodeWithText(text)
}
