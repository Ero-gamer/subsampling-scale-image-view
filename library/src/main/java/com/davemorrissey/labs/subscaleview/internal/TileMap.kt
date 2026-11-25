package com.davemorrissey.labs.subscaleview.internal

internal class TileMap : LinkedHashMap<Int, List<Tile>>() {

	fun recycleAll() {
		for ((_, tiles) in this) {
			for (tile in tiles) {
				tile.recycle()
			}
		}
	}

	fun invalidateAll() {
		for ((_, tiles) in this) {
			for (tile in tiles) {
				tile.isValid = false
				tile.failCount = 0
				tile.failedAt = 0L
			}
		}
	}

	fun hasMissingTiles(sampleSize: Int): Boolean {
		val tiles = this[sampleSize] ?: return true
		return tiles.any { tile ->
			tile.isVisible && (tile.isLoading || tile.bitmap == null)
		}
	}
}
