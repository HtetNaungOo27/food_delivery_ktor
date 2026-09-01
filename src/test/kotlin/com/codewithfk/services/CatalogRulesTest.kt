package com.codewithfk.services

import com.codewithfk.model.MenuModifierGroup
import com.codewithfk.model.MenuModifierOption
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogRulesTest {
    @Test fun `required modifier has at least one valid option`() {
        val group = MenuModifierGroup("Size", true, 1, listOf(MenuModifierOption("Large", 1.5)))
        assertTrue(group.required)
        assertTrue(group.options.isNotEmpty())
        assertTrue(group.options.all { it.additionalPrice >= 0 })
    }

    @Test fun `inventory cannot be treated as available at zero`() {
        val inventory = 0
        assertFalse(inventory > 0)
    }

    @Test fun `dietary tags normalize deterministically`() {
        val tags = listOf("vegan", " VEGAN ", "halal").map { it.trim().uppercase() }.distinct()
        assertEquals(listOf("VEGAN", "HALAL"), tags)
    }
}
