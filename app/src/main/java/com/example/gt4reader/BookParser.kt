package com.example.gt4reader

import java.io.File
import java.nio.charset.Charset

/**
 * txt 小说解析器：把一整个 txt 切成章节列表。
 *
 * 输出格式与手表端约定一致：
 *   Book(name, chapters=[Chapter(title, text)])
 * 手表端 index.js 的 blocks 就是照这个结构生成的。
 */
object BookParser {

    data class Chapter(val title: String, val text: String)
    data class Book(val name: String, val chapters: List<Chapter>, val encoding: String = "UTF-8")

    /** 章节标题正则：第X章 / 第X节 / 第X回 等 */
    private val TITLE_RE = Regex(
        """^\s*第\s*([0-9零一二两三四五六七八九十百千万]{1,12})\s*[章节回卷篇]\s*(.*)$"""
    )

    /** 标题里不应出现的收尾标点（防止正文句子被误判成标题） */
    private val END_PUNCT = setOf('，', '。', '！', '？', '、', '；', '：', '”', '’', ',', '.', '!', '?')

    /** 标题最大长度，超过就认为不是标题 */
    private const val TITLE_MAX_LEN = 30

    /** 兜底：整本没识别出章节时，按多少字切一章 */
    private const val FALLBACK_CHARS = 2000

    /**
     * 解析文件。自动识别 UTF-8 / GBK。
     */
    fun parse(file: File): Book {
        val (raw, enc) = readWithEncoding(file)
        val name = file.nameWithoutExtension
        return parseString(raw, name, enc)
    }

    fun parseString(raw: String, name: String, encoding: String = "UTF-8"): Book {
        val chapters = splitChapters(raw)
        return Book(name, chapters, encoding)
    }

    /** 编码识别：先按 UTF-8 严格解码，失败则回退 GBK */
    fun readTextAuto(file: File): String = readWithEncoding(file).first

    /** 同上，但把识别出的编码一起返回 */
    fun readWithEncoding(file: File): Pair<String, String> {
        val bytes = file.readBytes()
        return try {
            // 严格模式，非法序列会抛异常
            val decoder = Charset.forName("UTF-8").newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK")) to "GBK"
        }
    }

    private fun splitChapters(raw: String): List<Chapter> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        val found = mutableListOf<Pair<String, StringBuilder>>()
        var current: Pair<String, StringBuilder>? = null

        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue

            val title = matchTitle(t)
            if (title != null) {
                current?.let { found.add(it) }
                current = title to StringBuilder()
            } else {
                if (current == null) current = "前言" to StringBuilder()
                current.second.append(t).append('\n')
            }
        }
        current?.let { found.add(it) }

        val result = found
            .map { (title, body) -> Chapter(title, body.toString().trim()) }
            .filter { it.text.isNotEmpty() }

        if (result.isNotEmpty()) return result

        // 兜底：没识别出任何章节，按字数切
        return fallbackSplit(raw)
    }

    private fun matchTitle(line: String): String? {
        val m = TITLE_RE.matchEntire(line) ?: return null

        // 防护 1：以标点结尾的，多半是正文里的句子（如"第二章的内容下回分解。"）
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return null
        if (rest.last() in END_PUNCT) return null

        // 防护 2：标题过长，不像标题
        val full = line.trim()
        if (full.length > TITLE_MAX_LEN) return null

        return full
    }

    private fun fallbackSplit(raw: String): List<Chapter> {
        val body = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<Chapter>()
        var i = 0
        var idx = 1
        while (i < body.length) {
            val end = (i + FALLBACK_CHARS).coerceAtMost(body.length)
            out.add(Chapter("第${idx}章", body.substring(i, end).trim()))
            i = end
            idx++
        }
        return out
    }
}
