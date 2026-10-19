package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.CustomEquipmentItem
import com.devil.phoenixproject.domain.model.CustomEquipmentPreferences

/**
 * One user edit of the custom-equipment list (issue #1227), applied to the latest stored
 * list rather than to the copy a screen rendered. The result still goes through
 * [ProfilePreferencesValidator.customEquipment] on write.
 */
sealed interface CustomEquipmentEdit {
    fun applyTo(document: CustomEquipmentPreferences): CustomEquipmentPreferences

    /** Mints the token once, from the label as typed (signoff B1). */
    data class Add(val label: String, val createdAt: Long) : CustomEquipmentEdit {
        override fun applyTo(document: CustomEquipmentPreferences): CustomEquipmentPreferences {
            val trimmed = label.trim()
            val token = CustomEquipmentContract.tokenFor(trimmed) ?: return document
            return document.copy(items = document.items + CustomEquipmentItem(token, trimmed, createdAt))
        }
    }

    /** Moves the label only; the token and createdAt stay as stored (signoff B1). */
    data class Rename(val token: String, val label: String) : CustomEquipmentEdit {
        override fun applyTo(document: CustomEquipmentPreferences): CustomEquipmentPreferences = document.copy(
            items = document.items.map { if (it.token == token) it.copy(label = label.trim()) else it },
        )
    }

    /** Drops the picker entry; exercises using the token are never touched (signoff B2). */
    data class Remove(val token: String) : CustomEquipmentEdit {
        override fun applyTo(document: CustomEquipmentPreferences): CustomEquipmentPreferences =
            document.copy(items = document.items.filterNot { it.token == token })
    }
}
