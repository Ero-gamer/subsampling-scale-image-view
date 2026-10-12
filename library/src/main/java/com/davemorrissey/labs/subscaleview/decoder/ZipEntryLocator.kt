package com.davemorrissey.labs.subscaleview.decoder

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Finds where a STORED (uncompressed) entry's bytes live inside a ZIP/CBZ file, so the decoder can
 * memory-map them in place instead of copying the entry into memory.
 *
 * Only the central directory is parsed (once per archive, cached). ZIP64, encrypted and compressed
 * entries are reported as "not locatable" and the caller falls back to the regular path.
 */
internal object ZipEntryLocator {

	private const val EOCD_SIG = 0x06054b50
	private const val CEN_SIG = 0x02014b50
	private const val LOC_SIG = 0x04034b50
	private const val EOCD_MIN = 22
	private const val MAX_COMMENT = 0xFFFF
	private const val MAX_CEN_BYTES = 32 * 1024 * 1024
	private const val MAX_CACHED_ARCHIVES = 4
	private const val U32_MAX = 0xFFFFFFFFL

	private class Entry(val compressedSize: Long, val size: Long, val headerOffset: Long)

	private class Index(val entries: Map<String, Entry>?)

	private val cache = object : LinkedHashMap<String, Index>(8, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Index>?): Boolean = size > MAX_CACHED_ARCHIVES
	}

	/** `{dataOffset, length}` of the stored entry [name], or null when it must be read the normal way. */
	fun locateStored(archive: File, name: String): LongArray? {
		val length = archive.length()
		if (length < EOCD_MIN) return null
		val key = "${archive.path}|$length|${archive.lastModified()}"
		val index = synchronized(cache) { cache[key] } ?: buildIndex(archive, length).also {
			synchronized(cache) { cache[key] = it }
		}
		val entry = index.entries?.get(name) ?: return null
		if (entry.size <= 0 || entry.compressedSize != entry.size) return null
		return try {
			RandomAccessFile(archive, "r").use { raf ->
				val head = ByteArray(30)
				raf.seek(entry.headerOffset)
				raf.readFully(head)
				if (u32(head, 0) != (LOC_SIG.toLong() and U32_MAX)) return null
				val dataOffset = entry.headerOffset + 30 + u16(head, 26) + u16(head, 28)
				if (dataOffset + entry.size > length) null else longArrayOf(dataOffset, entry.size)
			}
		} catch (_: IOException) {
			null
		}
	}

	private fun buildIndex(archive: File, length: Long): Index = try {
		Index(parse(archive, length))
	} catch (_: Exception) {
		Index(null)
	}

	private fun parse(archive: File, length: Long): Map<String, Entry>? = RandomAccessFile(archive, "r").use { raf ->
		val tailLen = minOf(length, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
		val tail = ByteArray(tailLen)
		raf.seek(length - tailLen)
		raf.readFully(tail)
		var p = tailLen - EOCD_MIN
		while (p >= 0) {
			if (u32(tail, p) == (EOCD_SIG.toLong() and U32_MAX) && p + EOCD_MIN + u16(tail, p + 20) == tailLen) break
			p--
		}
		if (p < 0) return null
		val count = u16(tail, p + 10)
		val cenSize = u32(tail, p + 12)
		val cenOffset = u32(tail, p + 16)
		// ZIP64 markers: leave those archives to the regular reader.
		if (count == 0xFFFF || cenSize == U32_MAX || cenOffset == U32_MAX) return null
		if (cenSize > MAX_CEN_BYTES || cenOffset + cenSize > length) return null
		val cen = ByteArray(cenSize.toInt())
		raf.seek(cenOffset)
		raf.readFully(cen)
		val map = HashMap<String, Entry>(count * 2)
		var o = 0
		while (o + 46 <= cen.size && u32(cen, o) == (CEN_SIG.toLong() and U32_MAX)) {
			val flags = u16(cen, o + 8)
			val method = u16(cen, o + 10)
			val compressed = u32(cen, o + 20)
			val size = u32(cen, o + 24)
			val nameLen = u16(cen, o + 28)
			val extraLen = u16(cen, o + 30)
			val commentLen = u16(cen, o + 32)
			val localOffset = u32(cen, o + 42)
			if (o + 46 + nameLen > cen.size) break
			val plain = method == 0 && (flags and 1) == 0 &&
				compressed != U32_MAX && size != U32_MAX && localOffset != U32_MAX
			if (plain) {
				map[String(cen, o + 46, nameLen, Charsets.UTF_8)] = Entry(compressed, size, localOffset)
			}
			o += 46 + nameLen + extraLen + commentLen
		}
		map
	}

	private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

	private fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
}
