package com.insituledger.app.ui.categories

import com.insituledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFormLogicTest {

    private fun category(id: Long, name: String, parentId: Long? = null, type: String = "expense") =
        Category(id = id, userId = 1, parentId = parentId, name = name, type = type, icon = null, color = null)

    private val food = category(1, "Food")
    private val groceries = category(2, "Groceries", parentId = 1)
    private val rent = category(3, "Rent")
    private val salary = category(4, "Salary", type = "income")
    private val bonus = category(5, "Bonus", parentId = 4, type = "income")
    private val all = listOf(food, groceries, rent, salary, bonus)

    @Test
    fun `parent options are top-level categories of the same type`() {
        assertEquals(listOf("Food", "Rent"), categoryParentOptions(all, "expense", selfId = null).map { it.name })
        assertEquals(listOf("Salary"), categoryParentOptions(all, "income", selfId = null).map { it.name })
    }

    @Test
    fun `parent options never include the category itself`() {
        assertEquals(listOf("Rent"), categoryParentOptions(all, "expense", selfId = food.id).map { it.name })
    }

    @Test
    fun `subcategories are never offered as parents`() {
        val options = categoryParentOptions(all, "expense", selfId = rent.id)
        assertFalse(options.any { it.id == groceries.id })
    }

    @Test
    fun `has subcategories only when another category points at it`() {
        assertTrue(categoryHasSubcategories(all, food.id))
        assertFalse(categoryHasSubcategories(all, rent.id))
        assertFalse(categoryHasSubcategories(all, groceries.id))
        assertFalse(categoryHasSubcategories(all, null))
    }

    @Test
    fun `type change keeps a parent of the new type`() {
        assertEquals(salary.id, parentAfterTypeChange(all, salary.id, "income"))
    }

    @Test
    fun `type change clears a parent of the other type`() {
        assertNull(parentAfterTypeChange(all, food.id, "income"))
    }

    @Test
    fun `type change clears an unknown parent and keeps none as none`() {
        assertNull(parentAfterTypeChange(all, 99, "expense"))
        assertNull(parentAfterTypeChange(all, null, "income"))
    }

    @Test
    fun `ui state derives options and the subcategory lock`() {
        val state = CategoryFormUiState(id = food.id, type = "expense", allCategories = all)
        assertEquals(listOf("Rent"), state.parentOptions.map { it.name })
        assertTrue(state.hasSubcategories)
        assertFalse(state.copy(id = rent.id).hasSubcategories)
    }
}
