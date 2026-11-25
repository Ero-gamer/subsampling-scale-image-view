package com.davemorrissey.labs.subscaleview.internal

import com.davemorrissey.labs.subscaleview.OnImageEventListener

internal class CompositeImageEventListener : OnImageEventListener {

	private var listeners: MutableList<OnImageEventListener>? = null

	override fun onReady() {
		snapshot()?.forEach { it.onReady() }
	}

	override fun onImageLoaded() {
		snapshot()?.forEach { it.onImageLoaded() }
	}

	override fun onPreviewLoadError(e: Throwable) {
		snapshot()?.forEach { it.onPreviewLoadError(e) }
	}

	override fun onImageLoadError(e: Throwable) {
		snapshot()?.forEach { it.onImageLoadError(e) }
	}

	override fun onTileLoadError(e: Throwable) {
		snapshot()?.forEach { it.onTileLoadError(e) }
	}

	override fun onPreviewReleased() {
		snapshot()?.forEach { it.onPreviewReleased() }
	}

	private fun snapshot(): List<OnImageEventListener>? = listeners?.toList()

	fun addListener(listener: OnImageEventListener) {
		listeners?.add(listener) ?: run {
			listeners = arrayListOf(listener)
		}
	}

	fun removeListener(listener: OnImageEventListener) {
		listeners?.remove(listener)
	}

	fun clearListeners() {
		listeners = null
	}
}
