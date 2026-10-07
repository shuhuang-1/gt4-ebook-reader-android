package com.me.gt4reader.phone

import com.me.gt4reader.phone.BookParser.Book
import org.json.JSONObject

/**
 * 传输层。
 *
 * 职责单一：只负责「把一条字符串送到手表」，不管内容是什么。
 * 协议组装（begin/chunk/end）交给 Pusher，传输实现换掉即可：
 *
 *   现在      → MockTransport        （不真发，只走进度，用来验证上层链路）
 *   权限批了  → WearEngineTransport  （换成真发，上层一行都不用改）
 */
interface Transport {
    /** 是否已就绪（真实现里检查：手表是否连接、手表端 App 是否在运行） */
    fun isReady(): Boolean

    /** 就绪状态的文字说明，直接显示给用户 */
    fun statusText(): String

    /**
     * 发送一条报文。
     * @param text    报文内容（单行 JSON）
     * @param onResult (成功?, 错误信息) —— 在主线程回调
     */
    fun sendRaw(text: String, onResult: (Boolean, String?) -> Unit)
}

/** 现在用的假实现：不真发，只走进度，用来验证上层链路 */
class MockTransport : Transport {

    override fun isReady(): Boolean = true
    override fun statusText(): String = "模拟模式（未接 Wear Engine）"

    override fun sendRaw(text: String, onResult: (Boolean, String?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onResult(true, null)
        }, 30)
    }
}

// ---------------------------------------------------------------------------

/**
 * 分片器：把整本书切成若干条「装得下」的报文。
 *
 * 难点在于——4KB 是**字节**上限，而中文一个字 3 字节，
 * 所以不能用 String.length 判断，必须按 UTF-8 字节算，
 * 而且 JSON 转义（\n \" 等）还会额外膨胀，必须按**最终报文**实测。
 */
object Splitter {

    /**
     * 估算报文大小时用的「最坏情况序号」。
     * 因为分片阶段还不知道最终 total 是几位数，
     * 若按 i=0/total=0 估算，实际序号变成 17、23 时会多出几个字节导致超限。
     * 取 8 位数封顶，宁可少装几个字，也不能超限。
     */
    private const val SENTINEL = 99999999

    private fun measure(id: String, chapters: List<JSONObject>): Int =
        Proto.bytes(Proto.chunk(id, SENTINEL, SENTINEL, chapters))

    /**
     * 把一本书切成若干片。
     * @return 每片是一个「章节 JSON 列表」，还没包成最终报文
     */
    fun buildChunks(id: String, book: Book, maxBytes: Int = Proto.MAX_BYTES): List<List<JSONObject>> {
        val out = mutableListOf<List<JSONObject>>()
        var cur = mutableListOf<JSONObject>()
        var curBytes = 0

        fun currentSize(): Int = measure(id, cur)

        for (ch in book.chapters) {
            val item = Proto.chapter(ch.title, ch.text)
            cur.add(item)

            if (currentSize() > maxBytes) {
                // 装不下了，先把已有的（不含刚加的这个）吐出去
                cur.removeAt(cur.size - 1)
                if (cur.isNotEmpty()) {
                    out.add(cur)
                    cur = mutableListOf()
                }

                // 单独放这一章，如果还是超限 → 说明这一章本身就太大，要切正文
                cur.add(item)
                if (currentSize() > maxBytes) {
                    cur.removeAt(cur.size - 1)
                    val slices = splitLongChapter(id, ch.title, ch.text, maxBytes)
                    // 前 n-1 片各自成片（因为每片都已接近上限）
                    slices.forEach { out.add(listOf(it)) }
                }
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * 单章超过上限时，按字节切正文。
     * 后续切片带 cont=true，手表端拼到上一条章节的正文后面。
     */
    private fun splitLongChapter(
        id: String, title: String, text: String, maxBytes: Int
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var start = 0
        var first = true

        while (start < text.length) {
            val take = maxFit(id, title, text, start, first, maxBytes)
            val slice = text.substring(start, start + take)
            result.add(Proto.chapter(title, slice, cont = !first))
            start += take
            first = false
        }
        return result
    }

    /** 二分查找：从 start 起最多能取多少个字符仍不超限 */
    private fun maxFit(
        id: String, title: String, text: String, start: Int, first: Boolean, maxBytes: Int
    ): Int {
        fun fits(n: Int): Boolean {
            val s = text.substring(start, (start + n).coerceAtMost(text.length))
            return measure(id, listOf(Proto.chapter(title, s, !first))) <= maxBytes
        }

        var lo = 1
        var hi = text.length - start
        // 先按「最坏 3 字节/字」估一个上界，避免二分范围过大
        hi = hi.coerceAtMost(maxBytes) // 字符数不可能超过字节上限
        if (fits(hi)) return hi

        // 二分出最大可容纳字符数
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (fits(mid)) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(1)
    }
}

// ---------------------------------------------------------------------------

/**
 * 推送编排：begin → chunk × N → end，带进度回调和失败中断。
 *
 * 支持断点续传：startFrom > 0 时跳过前面的片（用于收到手表 ack 之后续推）。
 */
class Pusher(private val transport: Transport) {

    /**
     * @param onProgress (已发片数, 总片数)
     * @param onDone     (是否成功, 说明文字)
     */
    fun push(
        preChunks: List<List<JSONObject>>? = null,
        book: Book,
        startFrom: Int = 0,
        onProgress: (Int, Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        if (!transport.isReady()) {
            onDone(false, "未就绪：${transport.statusText()}")
            return
        }

        val id = Proto.bookId(book.name)
        val chunks = preChunks ?: Splitter.buildChunks(id, book)
        val total = chunks.size

        if (total == 0) {
            onDone(false, "没有可发送的内容")
            return
        }

        fun sendChunk(i: Int) {
            if (i >= total) {
                transport.sendRaw(Proto.end(id, book.chapters.size)) { ok, err ->
                    if (ok) onDone(true, "完成：$total 片 / ${book.chapters.size} 章")
                    else onDone(false, "结束报文发送失败：$err")
                }
                return
            }
            val msg = Proto.chunk(id, i, total, chunks[i])
            transport.sendRaw(msg) { ok, err ->
                if (!ok) {
                    onDone(false, "第 $i/$total 片失败：$err（可稍后续传，已发 $i 片）")
                    return@sendRaw
                }
                onProgress(i + 1, total)
                sendChunk(i + 1)
            }
        }

        // 断点续传时跳过已发的片
        if (startFrom > 0) {
            onProgress(startFrom, total)
            sendChunk(startFrom)
        } else {
            transport.sendRaw(Proto.begin(id, book.name, book.chapters.size, total)) { ok, err ->
                if (!ok) {
                    onDone(false, "开始报文发送失败：$err")
                    return@sendRaw
                }
                sendChunk(0)
            }
        }
    }
}
