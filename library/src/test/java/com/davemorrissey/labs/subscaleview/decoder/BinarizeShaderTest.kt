package com.davemorrissey.labs.subscaleview.decoder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinarizeShaderTest {

    private val shader: String by lazy { File("src/main/assets/binarize.frag").readText() }
    private val renderer: String by lazy {
        File("src/main/java/com/davemorrissey/labs/subscaleview/decoder/GpuTileRenderer.kt").readText()
    }

    @Test
    fun declaresEverythingTheRendererSets() {
        assertTrue(shader.startsWith("#version 300 es"))
        for (name in listOf("u_texture", "u_texelSize", "u_strength", "u_radius")) {
            assertTrue("uniform $name", shader.contains("uniform") && shader.contains(name))
            assertTrue("renderer looks up $name", renderer.contains("\"$name\""))
        }
        assertTrue(shader.contains("void main()"))
    }

    @Test
    fun usesTheDocumentedFallbackAndEightNeighbours() {
        assertTrue(shader.contains("LOW_CONTRAST = 0.15"))
        assertTrue(shader.contains("FIXED_THRESHOLD = 0.5"))
        assertEquals(8, Regex("""float l[1-8] = luma\(""").findAll(shader).count())
        assertEquals(shader.count { it == '{' }, shader.count { it == '}' })
    }

    @Test
    fun radiusStaysInsideTheDecodeApron() {
        val radius = Regex("""BINARIZE_RADIUS_PX = (\d+(\.\d+)?)f""").find(renderer)!!.groupValues[1].toFloat()
        val apron = Regex("""const val APRON_PX = (\d+)""")
            .find(File("src/main/java/com/davemorrissey/labs/subscaleview/decoder/GpuFilteringDecoder.kt").readText())!!
            .groupValues[1].toInt()
        assertTrue("radius $radius must fit the $apron px apron", radius <= apron)
    }
}
