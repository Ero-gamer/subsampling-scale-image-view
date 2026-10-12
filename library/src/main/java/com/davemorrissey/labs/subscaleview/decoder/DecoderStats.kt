package com.davemorrissey.labs.subscaleview.decoder

import java.util.concurrent.atomic.AtomicLong

/** Cheap process-wide counters for the region decoders (shown by the app's debug screen). */
public object DecoderStats {

	private val nativeTiles = AtomicLong()
	private val nativeNanos = AtomicLong()
	private val skiaTiles = AtomicLong()
	private val skiaNanos = AtomicLong()
	private val skiaFallbacks = AtomicLong()
	private val extractions = AtomicLong()

	public data class Snapshot(
		val nativeTiles: Long,
		val nativeMillis: Long,
		val skiaTiles: Long,
		val skiaMillis: Long,
		val skiaFallbacks: Long,
		val extractions: Long,
	)

	public fun snapshot(): Snapshot = Snapshot(
		nativeTiles = nativeTiles.get(),
		nativeMillis = nativeNanos.get() / NANOS_PER_MILLI,
		skiaTiles = skiaTiles.get(),
		skiaMillis = skiaNanos.get() / NANOS_PER_MILLI,
		skiaFallbacks = skiaFallbacks.get(),
		extractions = extractions.get(),
	)

	public fun reset() {
		nativeTiles.set(0)
		nativeNanos.set(0)
		skiaTiles.set(0)
		skiaNanos.set(0)
		skiaFallbacks.set(0)
		extractions.set(0)
	}

	internal fun onNativeTile(nanos: Long) {
		nativeTiles.incrementAndGet()
		nativeNanos.addAndGet(nanos)
	}

	internal fun onSkiaTile(nanos: Long) {
		skiaTiles.incrementAndGet()
		skiaNanos.addAndGet(nanos)
	}

	/** A JPEG tile that had to be decoded by Skia (unsupported sample size / native failure). */
	internal fun onSkiaFallback() {
		skiaFallbacks.incrementAndGet()
	}

	internal fun onExtraction() {
		extractions.incrementAndGet()
	}

	private const val NANOS_PER_MILLI = 1_000_000L
}
