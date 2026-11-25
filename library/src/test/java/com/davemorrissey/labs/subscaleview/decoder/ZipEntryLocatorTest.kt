package com.davemorrissey.labs.subscaleview.decoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipEntryLocatorTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun zip(name: String, build: ZipOutputStream.() -> Unit): File {
		val file = tmp.newFile(name)
		ZipOutputStream(FileOutputStream(file)).use(build)
		return file
	}

	private fun ZipOutputStream.stored(name: String, data: ByteArray) {
		val e = ZipEntry(name)
		e.method = ZipEntry.STORED
		e.size = data.size.toLong()
		e.compressedSize = data.size.toLong()
		e.crc = CRC32().also { it.update(data) }.value
		putNextEntry(e)
		write(data)
		closeEntry()
	}

	private fun ZipOutputStream.deflated(name: String, data: ByteArray) {
		putNextEntry(ZipEntry(name))
		write(data)
		closeEntry()
	}

	@Test
	fun storedEntryIsLocatedAtItsBytes() {
		val a = ByteArray(1000) { (it % 251).toByte() }
		val b = ByteArray(2048) { (it % 13).toByte() }
		val file = zip("s.zip") {
			stored("a.jpg", a)
			stored("dir/b.jpg", b)
		}
		for ((name, data) in listOf("a.jpg" to a, "dir/b.jpg" to b)) {
			val loc = ZipEntryLocator.locateStored(file, name)
			assertNotNull(name, loc)
			assertEquals(data.size.toLong(), loc!![1])
			val read = ByteArray(data.size)
			java.io.RandomAccessFile(file, "r").use {
				it.seek(loc[0])
				it.readFully(read)
			}
			assertArrayEquals(data, read)
		}
	}

	@Test
	fun deflatedAndMissingEntriesAreNotLocatable() {
		val file = zip("d.zip") {
			deflated("c.jpg", ByteArray(5000))
			stored("s.jpg", ByteArray(10) { 1 })
		}
		assertNull(ZipEntryLocator.locateStored(file, "c.jpg"))
		assertNull(ZipEntryLocator.locateStored(file, "nope.jpg"))
		assertNotNull(ZipEntryLocator.locateStored(file, "s.jpg"))
	}

	@Test
	fun emptyEntryIsNotLocatable() {
		val file = zip("e.zip") { stored("empty.jpg", ByteArray(0)) }
		assertNull(ZipEntryLocator.locateStored(file, "empty.jpg"))
	}

	@Test
	fun notAZipOrTinyFileIsNull() {
		val junk = tmp.newFile("junk.zip").also { it.writeBytes(ByteArray(500) { 7 }) }
		assertNull(ZipEntryLocator.locateStored(junk, "a.jpg"))
		val tiny = tmp.newFile("tiny.zip").also { it.writeBytes(ByteArray(5)) }
		assertNull(ZipEntryLocator.locateStored(tiny, "a.jpg"))
	}
}
