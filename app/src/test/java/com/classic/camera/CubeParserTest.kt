package com.classic.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CubeParserTest {

    private fun asset(name: String): File =
        File("src/main/assets/filters", name)

    @Test
    fun parsesRicohCube() {
        val f = asset("理光正片.cube")
        assertTrue("test asset missing: ${f.absolutePath}", f.exists())

        val data = CubeParser.parse(f.readBytes())
        assertNotNull(data)
        data!!

        // 33³ × 3
        assertEquals(33 * 33 * 33 * 3, data.size)
        // 文件首行数据：0.009081 0.003968 0.009116
        assertEquals(0.009081f, data[0], 1e-6f)
        assertEquals(0.003968f, data[1], 1e-6f)
        assertEquals(0.009116f, data[2], 1e-6f)
        // 所有分量应在 [0,1]
        for (v in data) assertTrue("out of range: $v", v in -0.001f..1.001f)
    }

    @Test
    fun fastParserMatchesExpectedForAllPresets() {
        val dir = File("src/main/assets/filters")
        assertTrue(dir.isDirectory)
        val cubes = dir.listFiles { f -> f.isFile && f.name.endsWith(".cube") } ?: emptyArray()
        assertTrue("no .cube assets found", cubes.isNotEmpty())
        for (f in cubes) {
            val data = CubeParser.parse(f.readBytes())
            assertNotNull("parse failed: ${f.name}", data)
            data!!
            // size 必须是完全立方
            val n = data.size / 3
            var s = 1
            while (s * s * s < n) s++
            assertEquals("not cubic: ${f.name} (n=$n)", s * s * s, n)
        }
    }
}
