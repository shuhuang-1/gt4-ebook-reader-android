package com.example.gt4reader

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var btnPick: Button
    private lateinit var btnPush: Button
    private lateinit var btnAll: Button
    private lateinit var btnNone: Button
    private lateinit var tvInfo: TextView
    private lateinit var tvSel: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var rvChapters: RecyclerView
    private lateinit var pb: ProgressBar
    private lateinit var cardRecent: LinearLayout
    private lateinit var rowRecent: LinearLayout

    private var currentBook: BookParser.Book? = null
    private var currentUri: Uri? = null
    private var allChapters: List<BookParser.Chapter> = emptyList()

    private val selected = mutableSetOf<Int>()
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: ChapterAdapter

    private val transport: Transport = MockTransport()
    private lateinit var pusher: Pusher

    data class Row(val idx: Int, val title: String)
    data class RecentBook(val name: String, val uri: String, val chapters: Int, val time: Long)

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadBook(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.statusBarColor = getColor(R.color.bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        btnPick = findViewById(R.id.btnPick)
        btnPush = findViewById(R.id.btnPush)
        btnAll = findViewById(R.id.btnAll)
        btnNone = findViewById(R.id.btnNone)
        tvInfo = findViewById(R.id.tvInfo)
        tvSel = findViewById(R.id.tvSel)
        tvProgress = findViewById(R.id.tvProgress)
        tvStatus = findViewById(R.id.tvStatus)
        etSearch = findViewById(R.id.etSearch)
        rvChapters = findViewById(R.id.rvChapters)
        pb = findViewById(R.id.pb)
        cardRecent = findViewById(R.id.cardRecent)
        rowRecent = findViewById(R.id.rowRecent)

        rvChapters.layoutManager = LinearLayoutManager(this)
        adapter = ChapterAdapter(rows, selected) { updateSel() }
        rvChapters.adapter = adapter

        pusher = Pusher(transport)
        tvStatus.text = transport.statusText()

        btnPick.setOnClickListener { pickFile.launch(arrayOf("text/plain", "text/*", "*/*")) }
        btnPush.setOnClickListener { startPush() }
        btnAll.setOnClickListener { selectVisible(true) }
        btnNone.setOnClickListener { selectVisible(false) }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString() ?: "")
        })

        renderRecent()
        updateSel()
    }

    private fun loadBook(uri: Uri) {
        tvInfo.text = "解析中…"
        btnPush.isEnabled = false
        thread {
            try {
                val tmp = java.io.File(cacheDir, "book.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                val book = BookParser.parse(tmp)
                val id = Proto.bookId(book.name)

                runOnUiThread {
                    currentBook = book
                    currentUri = uri
                    allChapters = book.chapters
                    selected.clear()
                    selected.addAll(book.chapters.indices)

                    tvInfo.text = "《${book.name}》 ${book.chapters.size} 章\n" +
                            "编码 ${book.encoding}　id $id"
                    etSearch.setText("")
                    applyFilter("")
                    updateSel()
                }
            } catch (e: Exception) {
                runOnUiThread { tvInfo.text = "解析失败：${e.message}" }
            }
        }
    }

    private fun applyFilter(q: String) {
        val kw = q.trim()
        rows.clear()
        allChapters.forEachIndexed { i, ch ->
            if (kw.isEmpty() || ch.title.contains(kw, ignoreCase = true)) {
                rows.add(Row(i, ch.title))
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun selectVisible(select: Boolean) {
        rows.forEach { r ->
            if (select) selected.add(r.idx) else selected.remove(r.idx)
        }
        adapter.notifyDataSetChanged()
        updateSel()
    }

    private fun updateSel() {
        val n = selected.size
        tvSel.text = "已选 $n"
        btnPush.isEnabled = n > 0 && currentBook != null
        btnPush.text = if (n > 0) "推送 $n 章到手表" else "推送到手表"
    }

    private fun startPush() {
        val book = currentBook ?: return
        val picked = selected.sorted().map { allChapters[it] }
        if (picked.isEmpty()) {
            toast("请先选择要推送的章节")
            return
        }

        val sub = BookParser.Book(book.name, picked, book.encoding)

        btnPush.isEnabled = false
        btnPick.isEnabled = false
        pb.visibility = View.VISIBLE
        pb.progress = 0
        tvProgress.setTextColor(getColor(R.color.text2))
        tvProgress.text = "准备中…"

        thread {
            val chunks = Splitter.buildChunks(Proto.bookId(sub.name), sub)
            runOnUiThread {
                tvProgress.text = "共 ${chunks.size} 片，开始推送…"
                pusher.push(
                    book = sub,
                    preChunks = chunks,
                    onProgress = { sent, total ->
                        pb.progress = if (total == 0) 0 else sent * 100 / total
                        tvProgress.text = "推送中 $sent/$total 片"
                    },
                    onDone = { ok, msg ->
                        pb.visibility = View.GONE
                        tvProgress.text = msg
                        tvProgress.setTextColor(getColor(if (ok) R.color.ok else R.color.warn))
                        btnPush.isEnabled = true
                        btnPick.isEnabled = true
                        toast(msg)
                        if (ok) {
                            val b = currentBook
                            val u = currentUri
                            if (b != null && u != null) saveRecent(b.name, u, b.chapters.size)
                        }
                    }
                )
            }
        }
    }

    private fun prefs() = getSharedPreferences("gt4reader", MODE_PRIVATE)

    private fun loadRecent(): MutableList<RecentBook> {
        val arr = JSONArray(prefs().getString("recent", "[]") ?: "[]")
        val out = mutableListOf<RecentBook>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                RecentBook(
                    o.optString("name", "?"),
                    o.optString("uri", ""),
                    o.optInt("chapters", 0),
                    o.optLong("time", 0L)
                )
            )
        }
        out.sortByDescending { it.time }
        return out
    }

    private fun saveRecent(name: String, uri: Uri, chapters: Int) {
        val list = loadRecent().toMutableList()
        list.removeAll { it.uri == uri.toString() }
        list.add(0, RecentBook(name, uri.toString(), chapters, System.currentTimeMillis()))
        prefs().edit().putString("recent", toJson(list.take(8))).apply()
        runOnUiThread { renderRecent() }
    }

    private fun removeRecent(uri: String) {
        val list = loadRecent().toMutableList()
        list.removeAll { it.uri == uri }
        prefs().edit().putString("recent", toJson(list)).apply()
        renderRecent()
    }

    private fun toJson(list: List<RecentBook>): String {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject()
                    .put("name", b.name)
                    .put("uri", b.uri)
                    .put("chapters", b.chapters)
                    .put("time", b.time)
            )
        }
        return arr.toString()
    }

    private fun renderRecent() {
        val list = loadRecent()
        if (list.isEmpty()) {
            cardRecent.visibility = View.GONE
            return
        }
        cardRecent.visibility = View.VISIBLE
        rowRecent.removeAllViews()

        list.forEach { b ->
            val tv = TextView(this)
            tv.text = "${b.name} · ${b.chapters}章"
            tv.textSize = 12f
            tv.setTextColor(getColor(R.color.text1))
            tv.background = getDrawable(R.drawable.bg_chip)
            tv.setPadding(dp(12), dp(7), dp(12), dp(7))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, dp(8), 0)
            tv.layoutParams = lp

            tv.setOnClickListener {
                runCatching { loadBook(Uri.parse(b.uri)) }
                    .onFailure { toast("这本书打不开了，重新选一次吧") }
            }
            tv.setOnLongClickListener {
                removeRecent(b.uri)
                toast("已移出最近")
                true
            }
            rowRecent.addView(tv)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

class ChapterAdapter(
    private val rows: List<MainActivity.Row>,
    private val selected: MutableSet<Int>,
    private val onChange: () -> Unit
) : RecyclerView.Adapter<ChapterAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cb: CheckBox = view.findViewById(R.id.cbSel)
        val tv: TextView = view.findViewById(R.id.tvTitle)

        init {
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                val idx = rows[p].idx
                if (!selected.add(idx)) selected.remove(idx)
                notifyItemChanged(p)
                onChange()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = rows[position]
        val on = selected.contains(r.idx)
        holder.tv.text = r.title
        holder.cb.isChecked = on
        holder.itemView.isActivated = on
    }

    override fun getItemCount(): Int = rows.size
}