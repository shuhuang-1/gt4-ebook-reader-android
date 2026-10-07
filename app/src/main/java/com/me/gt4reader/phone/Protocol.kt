package com.me.gt4reader.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机（安卓 App） ↔ 手表（Lite Wearable） 传输协议 v1
 *
 * 设计前提：
 *  1. Wear Engine 单次传输上限不确定（官方口径 4KB ~ 100MB 矛盾），
 *     所以必须按「小片」推，不能整本一次发。
 *  2. Lite 手表端不能自由写文件，只能接收并读取，因此协议要做成
 *     「会话式」：begin → chunk* → end，手表端在内存里攒完再统一入库。
 *  3. 蓝牙传输可能中断，协议支持断点续传（query / ack）。
 *
 * 所有报文都是 UTF-8 的 JSON 单行字符串。
 * 字段一律用短名，为了省字节（4KB 上限下每个字节都值钱）。
 */
object Proto {

    const val VERSION = 1

    // 报文类型
    const val T_BEGIN  = "begin"   // 手机 → 手表：开始一本新书
    const val T_CHUNK  = "chunk"   // 手机 → 手表：一片章节数据
    const val T_END    = "end"     // 手机 → 手表：本书发完
    const val T_QUERY  = "query"   // 手机 → 手表：问某本书收到几片了
    const val T_ACK    = "ack"     // 手表 → 手机：回执，已收到 N 片
    const val T_ERR    = "err"     // 双向：出错

    /** 单条报文的字节上限（UTF-8）。保守取 4KB，留出 JSON 转义开销。
     *  ★ 权限批下来后第一件事：用真实值探测一次，再回来改这里。 */
    const val MAX_BYTES = 4 * 1024

    // ---------- 手机 → 手表 ----------

    /**
     * 开始会话。
     * @param id   书籍唯一 id（用书名的稳定哈希，重名书也能区分）
     * @param name 书名
     * @param n    总章节数
     * @param parts 预计总片数（可能与实际略有出入，手表端以实际收到的为准）
     */
    fun begin(id: String, name: String, n: Int, parts: Int): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_BEGIN)
        o.put("id", id)
        o.put("name", name)
        o.put("n", n)
        o.put("parts", parts)
        return o.toString()
    }

    /**
     * 一片章节数据。
     * @param i        片序号，从 0 开始
     * @param total    总片数
     * @param chapters 本片包含的章节
     */
    fun chunk(id: String, i: Int, total: Int, chapters: List<JSONObject>): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_CHUNK)
        o.put("id", id)
        o.put("i", i)
        o.put("total", total)
        val arr = JSONArray()
        chapters.forEach { arr.put(it) }
        o.put("chapters", arr)
        return o.toString()
    }

    /** 结束会话 */
    fun end(id: String, n: Int): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_END)
        o.put("id", id)
        o.put("n", n)
        return o.toString()
    }

    /** 询问进度（断点续传用） */
    fun query(id: String): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_QUERY)
        o.put("id", id)
        return o.toString()
    }

    // ---------- 手表 → 手机 ----------

    /** 回执：已收到的片数。got = -1 表示没有这本书的记录 */
    fun ack(id: String, got: Int): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_ACK)
        o.put("id", id)
        o.put("got", got)
        return o.toString()
    }

    fun err(id: String, msg: String): String {
        val o = JSONObject()
        o.put("v", VERSION)
        o.put("t", T_ERR)
        o.put("id", id)
        o.put("msg", msg)
        return o.toString()
    }

    // ---------- 章节序列化 ----------

    /**
     * 章节 → JSON。
     * @param cont  true 表示「本条是上一条章节正文的续片」，手表端应拼接而非新建章节
     */
    fun chapter(title: String, text: String, cont: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("title", title)
        o.put("text", text)
        if (cont) o.put("cont", true)
        return o
    }

    /** 计算字符串的实际 UTF-8 字节数（中文 3 字节，不能用 length） */
    fun bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** 书籍 id：书名的稳定哈希，避免重名冲突 */
    fun bookId(name: String): String =
        java.math.BigInteger(
            1, java.security.MessageDigest.getInstance("MD5")
                .digest(name.toByteArray(Charsets.UTF_8))
        ).toString(16).padStart(32, '0').substring(0, 12)
}
