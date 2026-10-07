package com.capital.accounting.finance

import com.capital.accounting.api.Category

fun categoryPath(id: String?, categories: List<Category>): String {
    if (id == null) return "Без категории"
    val byId = categories.associateBy { it.id }
    val seen = mutableSetOf<String>()
    val names = mutableListOf<String>()
    var current: String? = id
    while (current != null) {
        if (!seen.add(current)) return "Иерархия категории недоступна"
        val item = byId[current] ?: return "Категория недоступна"
        names.add(item.name)
        current = item.parent_id
    }
    return names.asReversed().joinToString(" / ")
}
