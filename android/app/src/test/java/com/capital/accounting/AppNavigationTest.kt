package com.capital.accounting

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationTest {
    @Test fun backRetracesSectionsSettingsAndFinancialRoutes() {
        val navigation = AppNavigation()
        navigation.section.value = "operations"
        navigation.section.value = "more"
        navigation.settingsGroup = "security"
        navigation.section.value = "operations"
        navigation.back()
        assertEquals("more", navigation.section.value)
        assertEquals("security", navigation.settingsGroup)
        navigation.back()
        assertEquals("profile", navigation.settingsGroup)
        navigation.back()
        assertEquals("operations", navigation.section.value)
        navigation.back()
        assertEquals("accounts", navigation.section.value)
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }

    @Test fun selectingCurrentSectionDoesNotAddAStep() {
        val navigation = AppNavigation()
        navigation.section.value = "operations"
        navigation.section.value = "operations"
        navigation.back()
        assertEquals("accounts", navigation.section.value)
    }
}
