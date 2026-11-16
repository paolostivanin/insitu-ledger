package com.insituledger.app.ui.categories

import com.insituledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {

    private fun category(id: Long, name: String, parentId: Long? = null) =
        Category(id = id, userId = 1, parentId = parentId, name = name, type = "expense", icon = null, color = null)

    private fun List<CategoryNode>.shape(): List<Pair<String, List<String>>> =
        map { it.category.name to it.descendants.map { d -> d.name } }

    @Test
    fun `children are listed under their parent`() {
        val tree = buildCategoryTree(
            listOf(category(1, "Food"), category(2, "Groceries", 1), category(3, "Rent"), category(4, "Dining", 1))
        )
        assertEquals(
            listOf("Food" to listOf("Groceries", "Dining"), "Rent" to emptyList()),
            tree.shape()
        )
    }

    @Test
    fun `grandchildren follow their own parent under the top-level ancestor`() {
        val tree = buildCategoryTree(
            listOf(
                category(1, "Food"),
                category(2, "Groceries", 1),
                category(3, "Dining", 1),
                category(4, "Organic", 2),
                category(5, "Local", 4)
            )
        )
        assertEquals(listOf("Food" to listOf("Groceries", "Organic", "Local", "Dining")), tree.shape())
    }

    @Test
    fun `a category whose parent is missing is shown as top-level`() {
        val tree = buildCategoryTree(listOf(category(1, "Rent"), category(2, "Orphan", 99)))
        assertEquals(listOf("Rent" to emptyList<String>(), "Orphan" to emptyList()), tree.shape())
    }

    @Test
    fun `categories in a parent cycle are still shown`() {
        val tree = buildCategoryTree(listOf(category(1, "A", 2), category(2, "B", 1), category(3, "Self", 3)))
        val shown = tree.flatMap { listOf(it.category.name) + it.descendants.map { d -> d.name } }
        assertEquals(setOf("A", "B", "Self"), shown.toSet())
        assertEquals(3, shown.size)
    }
}
