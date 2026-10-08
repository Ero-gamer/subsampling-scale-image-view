package com.davemorrissey.labs.subscaleview.internal

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.lang.ref.WeakReference

/**
 * Process-wide budget for decoded tile bitmaps.
 *
 * Each view only windows its own tiles, so the total used to depend on how many views happened to be
 * alive and what each had prefetched. The budget bounds the sum: tiles that are not on screen (the
 * prefetch / keep zone) are evicted, least recently used first, once the total passes it, and a
 * prefetch decode is only started while it fits. Tiles on screen and base layers are never evicted
 * here, so the budget can never blank what the reader is looking at.
 *
 * Main thread only.
 */
internal object TileMemory {

	private val views = ArrayList<WeakReference<SubsamplingScaleImageView>>()
	private val candidates = ArrayList<Tile>()
	private var projected = -1L

	fun register(view: SubsamplingScaleImageView) {
		views.add(WeakReference(view))
	}

	/** Bytes held by every live view's tile bitmaps (also drops references to collected views). */
	private fun usedBytes(): Long {
		var total = 0L
		val it = views.iterator()
		while (it.hasNext()) {
			val view = it.next().get()
			if (view == null) it.remove() else total += view.residentTileBytes()
		}
		return total
	}

	/** Starts a decode-admission pass: the next [tryReserve] measures the current usage once. */
	fun beginPass() {
		projected = -1L
	}

	/** True (and counted) when a new decode of about [bytes] still fits under [budget]. */
	fun tryReserve(
		bytes: Long,
		budget: Long,
	): Boolean {
		if (budget == Long.MAX_VALUE) return true
		if (projected < 0L) projected = usedBytes()
		if (projected + bytes > budget) return false
		projected += bytes
		return true
	}

	/** Evicts off-screen tiles, oldest first, until the total is back under [budget]. */
	fun enforce(budget: Long) {
		if (budget == Long.MAX_VALUE) return
		var used = usedBytes()
		if (used <= budget) return
		candidates.clear()
		for (ref in views) ref.get()?.collectEvictableTiles(candidates)
		candidates.sortBy { it.lastUsed }
		for (tile in candidates) {
			if (used <= budget) break
			used -= tile.byteCount
			tile.recycle()
		}
		candidates.clear()
	}
}
