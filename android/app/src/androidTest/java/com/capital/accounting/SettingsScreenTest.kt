package com.capital.accounting

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun serverAddressSurvivesActivityRecreation() {
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить адрес") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Адрес сервера").performTextClearance()
        compose.onNodeWithText("Адрес сервера").performTextInput("https://accounting.example/")
        compose.onNodeWithText("Сохранить адрес").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Адрес сохранен").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("https://accounting.example").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Capital").assertIsDisplayed()
    }
}
