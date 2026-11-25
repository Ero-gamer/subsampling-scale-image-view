package com.davemorrissey.labs.subscaleview.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalerShaderSourceTest {

    private fun sources() = mapOf(
        "glsl" to ScalerShaderSource.glslFragmentBody(),
        "agsl" to ScalerShaderSource.agslDpidShader(),
    )

    @Test
    fun dpidSourcesAreBalanced() {
        for ((name, src) in sources()) {
            assertEquals("$name braces", src.count { it == '{' }, src.count { it == '}' })
            assertEquals("$name parens", src.count { it == '(' }, src.count { it == ')' })
            assertTrue("$name has no unresolved Kotlin template", !src.contains("\$f") && !src.contains("\${"))
        }
    }

    @Test
    fun grayLinearLightPathIsDefinedBeforeUse() {
        for ((name, src) in sources()) {
            val def = src.indexOf("dpidGray(")
            val use = src.indexOf("return dpidGray(")
            assertTrue("$name defines dpidGray", def >= 0 && use > def)
            assertTrue("$name converts with the dot gain curve", src.contains("dotGainToLinear") && src.contains("dotGainFromLinear"))
            // The definition has to come before the call site, GLSL/SkSL have no forward declarations here.
            assertTrue("$name order", src.indexOf("dpidGray(") < use)
        }
    }

    @Test
    fun grayPathIsOpaqueOnly() {
        for ((name, src) in sources()) {
            assertTrue("$name alpha guard", src.contains("minAlpha >= 0.999"))
        }
    }
}
