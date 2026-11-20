package com.swissknife.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 日志裁剪的边界。
 *
 * 这段代码一年也未必在真机上触发几次（120 KB ≈ 7.8 天），但一旦写错，
 * 后果是**把用户现场证据毁掉**：截成半行、裁成空文件、或抛异常连带后续写入一起失败。
 * 所以在 JVM 上用临时文件把所有边界跑一遍，而不是等着真机碰运气。
 */
class LogFileTrimmerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "sk-trim-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    @Test
    fun `未超限时原样不动`() {
        val f = write("a.log", "line1\nline2\n")
        assertFalse(LogFileTrimmer.trimIfNeeded(f, maxBytes = 1000))
        assertEquals("line1\nline2\n", f.readText())
    }

    @Test
    fun `超限后只留尾部且从整行开始`() {
        val sb = StringBuilder()
        repeat(200) { sb.append("0123456789 line $it\n") }   // 每行 18 字节，共 3600
        val f = write("b.log", sb.toString())

        assertTrue(LogFileTrimmer.trimIfNeeded(f, maxBytes = 600, fraction = 0.5))

        val kept = f.readText()
        val lines = kept.trimEnd('\n').split('\n')
        // 不许留半行：每一行都必须是完整格式
        assertTrue("存在残行: ${lines.first()}", lines.all { it.startsWith("0123456789 line ") })
        // 保留的是最后的内容
        assertTrue("尾部内容丢失: ${lines.last()}", lines.last().contains("line 199"))
        assertTrue("裁完仍超限: ${kept.length}", kept.length <= 600)
    }

    @Test
    fun `整个文件只有一行时不裁剪`() {
        val f = write("c.log", "x".repeat(5000))
        assertFalse(LogFileTrimmer.trimIfNeeded(f, maxBytes = 600, fraction = 0.5))
        assertEquals(5000, f.length().toInt())
    }

    @Test
    fun `文件不存在时安全返回`() {
        assertFalse(LogFileTrimmer.trimIfNeeded(File(dir, "missing.log"), maxBytes = 100))
    }

    @Test
    fun `上限极小也不会写出空文件`() {
        val f = write("d.log", "aaa\nbbb\nccc\n")
        LogFileTrimmer.trimIfNeeded(f, maxBytes = 2, fraction = 0.5)
        // keep=1，找不到合适换行点 → 宁可不裁，也不要把文件弄空
        assertTrue("文件被清空", f.length() > 0)
    }
}