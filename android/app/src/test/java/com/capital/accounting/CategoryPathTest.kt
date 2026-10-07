package com.capital.accounting

import com.capital.accounting.api.Category
import com.capital.accounting.finance.categoryPath
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CategoryPathTest {
    private fun category(name: String, parent: String? = null) = Category(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "1", "2026-10-07T00:00:00Z", "2026-10-07T00:00:00Z", null, name, null, parent)
    @Test fun fullPathDistinguishesSameNamedLeaves() {
        val root = category("Home")
        val parent = category("Food", root.id)
        val leaf = category("Daily", parent.id)
        val other = category("Daily", root.id)
        val catalog = listOf(root, parent, leaf, other)
        assertEquals("Home / Food / Daily", categoryPath(leaf.id, catalog))
        assertEquals("Home / Daily", categoryPath(other.id, catalog))
        assertEquals("Без категории", categoryPath(null, catalog))
    }
    @Test fun missingParentsAndCyclesDoNotLoopOrInventHierarchy() {
        val leaf = category("Leaf", UUID.randomUUID().toString())
        assertEquals("Категория недоступна", categoryPath(leaf.id, listOf(leaf)))
        val cycle = leaf.copy(parent_id = leaf.id)
        assertEquals("Иерархия категории недоступна", categoryPath(cycle.id, listOf(cycle)))
    }
}
