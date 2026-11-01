package com.capital.accounting

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

/** Only complete screens enter history; forms and settings controls never add Back steps. */
internal class AppNavigation(routes: List<String> = listOf("accounts:profile")) {
    private var history by mutableStateOf(listOf("accounts"))
    private var group by mutableStateOf(routes.lastOrNull()?.substringAfter(':', "profile") ?: "profile")
    init {
        routes.forEach { route ->
            val screen = route.substringBefore(':')
            if (screen == "accounts") history = listOf("accounts")
            else if (screen in setOf("operations", "more") && screen != history.last()) history = history + screen
        }
    }
    val section: MutableState<String> = object : MutableState<String> {
        override var value: String
            get() = history.last()
            set(value) {
                require(value in setOf("accounts", "operations", "more"))
                if (value == "accounts") history = listOf("accounts")
                else if (history.last() != value) history = history + value
            }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    }
    var settingsGroup: String
        get() = group
        set(value) { group = value }

    fun back() {
        if (history.size > 1) history = history.dropLast(1)
    }
    companion object {
        val Saver = listSaver<AppNavigation, String>(
            save = { navigation -> navigation.history.map { "$it:${navigation.group}" } },
            restore = { AppNavigation(it) },
        )
    }
}
