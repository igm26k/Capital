package com.capital.accounting

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

/** Shared by bottom navigation and financial routes so every section change is reversible. */
internal class AppNavigation(routes: List<String> = listOf("accounts:profile")) {
    private var history by mutableStateOf(routes)
    private val current get() = history.last().split(':')
    val section: MutableState<String> = object : MutableState<String> {
        override var value: String
            get() = current[0]
            set(value) { visit(value, settingsGroup) }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    }
    var settingsGroup: String
        get() = current[1]
        set(value) { visit(section.value, value) }

    private fun visit(section: String, group: String) {
        val route = "$section:$group"
        if (history.last() != route) history = history + route
    }

    fun back() {
        if (history.size > 1) history = history.dropLast(1)
    }

    companion object {
        val Saver = listSaver<AppNavigation, String>(
            save = { it.history }, restore = { AppNavigation(it) },
        )
    }
}
