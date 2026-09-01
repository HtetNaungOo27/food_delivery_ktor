package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable
data class MenuItem(
    val id: String? = null,
    val restaurantId: String,
    val name: String,
    val description: String? = null,
    val price: Double,
    val imageUrl: String? = null,
    val arModelUrl: String? = null,
    val createdAt: String? = null,
    val isAvailable: Boolean = true,
    val unavailableUntil: String? = null,
    val inventoryQuantity: Int = 100,
    val dietaryTags: List<String> = emptyList(),
    val modifierGroups: List<MenuModifierGroup> = emptyList()
)

@Serializable data class MenuModifierGroup(val name: String, val required: Boolean = false, val maxSelections: Int = 1, val options: List<MenuModifierOption>)
@Serializable data class MenuModifierOption(val name: String, val additionalPrice: Double = 0.0)
