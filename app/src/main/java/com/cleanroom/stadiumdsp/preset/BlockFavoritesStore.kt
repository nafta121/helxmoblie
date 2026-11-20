package com.cleanroom.stadiumdsp.preset

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteBlockEntry(
    val id: String,
    val label: String,
    val category: BlockCategory,
    val modelId: Int,
    val params: Map<String, Float>,
)

/**
 * GAP-16: Per-model User Defaults and User Favorite Blocks library.
 */
class BlockFavoritesStore {
    private val _favorites = mutableListOf<FavoriteBlockEntry>()
    private val userDefaults = mutableMapOf<String, Map<String, Float>>()

    val favorites: List<FavoriteBlockEntry> get() = _favorites.toList()

    fun addFavorite(entry: FavoriteBlockEntry) {
        _favorites.removeAll { it.id == entry.id }
        _favorites.add(0, entry)
    }

    fun removeFavorite(id: String) {
        _favorites.removeAll { it.id == id }
    }

    fun saveUserDefault(modelKey: String, params: Map<String, Float>) {
        userDefaults[modelKey] = params.toMap()
    }

    fun getUserDefault(modelKey: String): Map<String, Float>? = userDefaults[modelKey]

    fun clearUserDefault(modelKey: String) {
        userDefaults.remove(modelKey)
    }
}
