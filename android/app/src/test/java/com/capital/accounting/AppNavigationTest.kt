package com.capital.accounting

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test fun backSkipsSettingsChangesAndReturnsThroughScreensToDashboard() {
        val navigation = AppNavigation()
        navigation.section.value = "operations"
        navigation.section.value = "more"
        navigation.settingsGroup = "accounts"
        navigation.settingsGroup = "classification"
        navigation.settingsGroup = "security"
        navigation.back()
        assertEquals("operations", navigation.section.value)
        navigation.back()
        assertEquals("accounts", navigation.section.value)
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }
    @Test fun selectingCurrentScreenDoesNotAddAStep() {
        val navigation = AppNavigation()
        navigation.section.value = "operations"
        navigation.section.value = "operations"
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }
    @Test fun dashboardIsAlwaysRootAndSelectingItClearsEarlierScreens() {
        val navigation = AppNavigation(listOf("more:security"))
        navigation.back()
        assertEquals("accounts", navigation.section.value)
        navigation.section.value = "operations"
        navigation.section.value = "more"
        navigation.section.value = "accounts"
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }
    @Test fun legacyHistoryCannotGoBehindTheDashboard() {
        val navigation = AppNavigation(listOf("accounts:profile", "operations:profile", "more:security", "accounts:security", "operations:security"))
        navigation.back()
        assertEquals("accounts", navigation.section.value)
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }

}
