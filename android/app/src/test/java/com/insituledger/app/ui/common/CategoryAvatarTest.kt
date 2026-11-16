package com.insituledger.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.insituledger.app.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryAvatarTest {

    private fun cat(id: Long, parentId: Long? = null, icon: String? = null) =
        Category(id = id, userId = 1, parentId = parentId, name = "C$id", type = "expense", icon = icon, color = null)

    @Test
    fun `emoji falls back to the parent's`() {
        val cats = listOf(cat(1, icon = "🏠"), cat(2, parentId = 1), cat(3, parentId = 1, icon = " 💡 "), cat(4))
        assertEquals("🏠", categoryEmoji(cats, 2))
        assertEquals("💡", categoryEmoji(cats, 3))
        assertNull(categoryEmoji(cats, 4))
        assertNull(categoryEmoji(cats, 99))
        assertNull(categoryEmoji(cats, null))
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    @Test
    fun `avatar initial clears AA on its tint for any category colour, both themes`() {
        // Representative app surfaces; the guarantee holds for any light/dark pair
        // whose onSurface itself contrasts strongly with surface.
        val themes = listOf(
            Triple(false, Color(0xFFFBFBFA), Color(0xFF1A1C1B)),
            Triple(true, Color(0xFF121413), Color(0xFFE3E5E4))
        )
        val steps = listOf(0, 0x33, 0x66, 0x99, 0xCC, 0xFF)
        for ((dark, surface, onSurface) in themes) {
            for (r in steps) for (g in steps) for (b in steps) {
                val base = Color(red = r, green = g, blue = b)
                val (container, content) = categoryAvatarColors(
                    base, dark, surface, onSurface,
                    neutralContainer = surface, neutralContent = onSurface
                )
                assertTrue(
                    "base=$base dark=$dark ratio=${contrast(content, container)}",
                    contrast(content, container) >= 4.5f
                )
            }
        }
    }
}
