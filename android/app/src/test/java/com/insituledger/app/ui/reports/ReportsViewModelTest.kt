package com.insituledger.app.ui.reports

import com.insituledger.app.data.local.db.dao.CategoryBreakdownRow
import com.insituledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsViewModelTest {

    private fun category(id: Long, name: String, parentId: Long? = null, type: String = "expense") =
        Category(id = id, userId = 1, parentId = parentId, name = name, type = type, icon = null, color = null)

    private fun row(categoryId: Long, total: Double, type: String = "expense") =
        CategoryBreakdownRow(categoryId = categoryId, type = type, total = total, count = 1)

    private val food = category(1, "Food")
    private val groceries = category(2, "Groceries", parentId = 1)
    private val dining = category(3, "Dining", parentId = 1)
    private val rent = category(4, "Rent")

    @Test
    fun `category grouping leaves each category on its own row`() {
        val out = buildBreakdown(
            listOf(row(1, 40.0), row(2, 100.0), row(3, 60.0)),
            listOf(food, groceries, dining),
            CategoryGrouping.CATEGORY
        )

        assertEquals(3, out.size)
        assertEquals(listOf("Groceries", "Dining", "Food"), out.map { it.category.name })
    }

    @Test
    fun `parent grouping sums children plus the parent's own transactions`() {
        val out = buildBreakdown(
            listOf(row(1, 40.0), row(2, 100.0), row(3, 60.0), row(4, 700.0)),
            listOf(food, groceries, dining, rent),
            CategoryGrouping.PARENT
        )

        assertEquals(2, out.size)
        assertEquals("Rent", out[0].category.name)
        assertEquals(700.0, out[0].total, 0.001)
        assertEquals("Food", out[1].category.name)
        assertEquals(200.0, out[1].total, 0.001)
    }

    @Test
    fun `parent grouping works when the parent has no transactions of its own`() {
        val out = buildBreakdown(
            listOf(row(2, 100.0), row(3, 60.0)),
            listOf(food, groceries, dining),
            CategoryGrouping.PARENT
        )

        assertEquals(1, out.size)
        assertEquals("Food", out[0].category.name)
        assertEquals(160.0, out[0].total, 0.001)
    }

    @Test
    fun `a child whose parent is gone falls back to itself instead of vanishing`() {
        val orphan = category(9, "Orphan", parentId = 99)
        val out = buildBreakdown(
            listOf(row(9, 30.0)),
            listOf(orphan),
            CategoryGrouping.PARENT
        )

        assertEquals(1, out.size)
        assertEquals("Orphan", out[0].category.name)
        assertEquals(30.0, out[0].total, 0.001)
    }

    @Test
    fun `income and expense children never merge into one parent figure`() {
        val sideGig = category(10, "Side gig", type = "income")
        val payout = category(11, "Payout", parentId = 10, type = "income")
        val fees = category(12, "Fees", parentId = 10, type = "expense")

        val out = buildBreakdown(
            listOf(row(11, 500.0, "income"), row(12, 30.0, "expense")),
            listOf(sideGig, payout, fees),
            CategoryGrouping.PARENT
        )

        assertEquals(2, out.size)
        assertTrue(out.all { it.category.name == "Side gig" })
        assertEquals(listOf(500.0, 30.0), out.map { it.total })
    }

    @Test
    fun `rows referencing an unknown category are dropped`() {
        val out = buildBreakdown(
            listOf(row(1, 40.0), row(777, 999.0)),
            listOf(food),
            CategoryGrouping.PARENT
        )

        assertEquals(1, out.size)
        assertEquals(40.0, out[0].total, 0.001)
        assertNull(out.find { it.category.id == 777L })
    }

    @Test
    fun `empty input yields an empty breakdown`() {
        assertTrue(buildBreakdown(emptyList(), listOf(food), CategoryGrouping.PARENT).isEmpty())
    }
}
