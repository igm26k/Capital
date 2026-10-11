package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Existing API scenarios explicitly navigate the new operation screen before interacting. */
fun ComposeContentTestRule.operationNode(text: String): SemanticsNodeInteraction {
    prepareOperationNavigation(text)
    waitUntil(10000) { onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithText(text)
}

fun ComposeContentTestRule.operationField(label: String): SemanticsNodeInteraction {
    prepareOperationNavigation(label)
    return onNodeWithText(label)
}

fun ComposeContentTestRule.prepareOperationNavigation(text: String) {
    if (onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) return
    val operationField = text in setOf("Сумма операции", "Примечание операции", "Получатель операции", "Дата операции (YYYY-MM-DDTHH:MM:SS)", "Часовой пояс операции") || text.matches(Regex("Сумма части [0-9]+"))
    val operationButton = text in setOf("Расход", "Доход", "Сохранить операцию", "Сохранить изменения операции", "Отменить изменение операции", "Использовать обновленную версию операции", "Добавить часть операции") || text.startsWith("Категория части ") || text.startsWith("Без категории для части ") || text.startsWith("Убрать часть ") || text.startsWith("Для операции: ")
    val transferTarget = text in setOf("Сумма списания перевода", "Сумма зачисления перевода", "Примечание перевода", "Примечание комиссии перевода", "Получатель перевода", "Дата перевода (YYYY-MM-DDTHH:MM:SS)", "Часовой пояс перевода", "Сумма комиссии перевода", "Сохранить перевод", "Сохранить изменения перевода", "Отменить изменение перевода", "Добавить комиссию перевода", "Убрать комиссию перевода", "Добавить часть комиссии", "Использовать обновленные версии перевода и комиссии") || listOf("Списать со счета: ", "Зачислить на счет: ", "Счет комиссии: ", "Сумма части комиссии ", "Категория комиссии ", "Без категории комиссии ", "Убрать часть комиссии ").any { text.startsWith(it) }
    val common = text in setOf("Вернуться к истории операций", "Вернуться к истории переводов", "Обновить счета", "Обновить категории и теги", "Обновить историю", "Повторить сохраненную команду", "Принять подтвержденный результат", "Обновить данные для новой команды", "Продолжить сохраненную команду в этой сессии", "Открыть черновик операции", "Открыть черновик перевода")
    val current = when {
        onAllNodesWithText("Вернуться к истории операций").fetchSemanticsNodes().isNotEmpty() -> "операции"
        onAllNodesWithText("Вернуться к истории переводов").fetchSemanticsNodes().isNotEmpty() -> "перевода"
        else -> null
    }
    val owns = if (current == "операции") operationField || operationButton || text.startsWith("Восстановить черновик операции ") || text.startsWith("Отбросить сохраненный черновик операции ") else transferTarget || text.startsWith("Восстановить черновик перевода ") || text.startsWith("Отбросить сохраненный черновик перевода ")
    if (current != null && !owns && !common) {
        val back = if (current == "операции") "Вернуться к истории операций" else "Вернуться к истории переводов"
        waitUntil(10000) { onAllNodes(hasText(back) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(back).performScrollTo().performClick()
        if (onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) {
            waitUntil(10000) { onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Отбросить черновик и продолжить").performClick()
        }
    }
    val opened = onAllNodesWithText("Вернуться к истории операций").fetchSemanticsNodes().isNotEmpty()
    if (!opened && (operationField || operationButton) && !(text in setOf("Расход", "Доход") || text.startsWith("Для операции: "))) {
        waitUntil(10000) { onAllNodes(hasText("Открыть черновик операции") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Открыть черновик операции").performScrollTo().performClick()
    }
    val transferOpen = onAllNodesWithText("Вернуться к истории переводов").fetchSemanticsNodes().isNotEmpty()
    if (!transferOpen && transferTarget && !text.startsWith("Списать со счета: ")) {
        waitUntil(10000) { onAllNodes(hasText("Открыть черновик перевода") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Открыть черновик перевода").performScrollTo().performClick()
    }
}
