package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Existing API scenarios explicitly navigate the new operation screen before interacting. */
fun ComposeContentTestRule.operationNode(text: String): SemanticsNodeInteraction {
    prepareOperationNavigation(text)
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
    val common = text in setOf("Вернуться к истории операций", "Обновить счета", "Обновить категории и теги", "Обновить историю", "Повторить сохраненную команду", "Принять подтвержденный результат", "Обновить данные для новой команды", "Продолжить сохраненную команду в этой сессии", "Открыть черновик операции") || text.startsWith("Восстановить черновик операции ") || text.startsWith("Отбросить сохраненный черновик операции ")
    val opened = onAllNodesWithText("Вернуться к истории операций").fetchSemanticsNodes().isNotEmpty()
    if (!opened && (operationField || operationButton) && !(text in setOf("Расход", "Доход") || text.startsWith("Для операции: "))) {
        waitUntil(10000) { onAllNodes(hasText("Открыть черновик операции") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Открыть черновик операции").performScrollTo().performClick()
    } else if (opened && !operationField && !operationButton && !common) {
        waitUntil(10000) { onAllNodes(hasText("Вернуться к истории операций") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Вернуться к истории операций").performScrollTo().performClick()
        if (onAllNodesWithText("Сохранить измененный черновик?").fetchSemanticsNodes().isNotEmpty()) {
            waitUntil(10000) { onAllNodes(hasText("Отбросить черновик и продолжить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Отбросить черновик и продолжить").performClick()
        }
    }
}
