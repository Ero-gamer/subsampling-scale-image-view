package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import java.io.File

/**
 * Bounded cache directory for JPEGs that cannot be memory-mapped in place (deflated CBZ entries,
 * content:// images). Everything in it is disposable; the decoder trims it on every extraction.
 */
public object JpegExtractCache {

	private const val DIR_NAME = "ssiv_jpeg"

	internal fun dir(context: Context): File = File(context.cacheDir, DIR_NAME).also { it.mkdirs() }

	/** `{fileCount, totalBytes}` currently held. */
	public fun usage(context: Context): LongArray {
		val files = File(context.cacheDir, DIR_NAME).listFiles() ?: return longArrayOf(0, 0)
		return longArrayOf(files.size.toLong(), files.sumOf { it.length() })
	}

	/** Deletes everything. Safe while images are open: mapped files stay valid after unlinking. */
	public fun clear(context: Context) {
		File(context.cacheDir, DIR_NAME).listFiles()?.forEach { it.delete() }
	}
}
