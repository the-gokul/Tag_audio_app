package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.databinding.ActivityLogViewerBinding
import com.nordic.tagmobile.log.TagLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private val adapter = LogAdapter()
    private val listener: (String) -> Unit = { line ->
        runOnUiThread {
            adapter.append(line)
            binding.logList.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backBtn.setOnClickListener { finish() }
        binding.shareBtn.setOnClickListener { shareLogs() }
        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = adapter
        adapter.setAll(TagLogger.snapshot())
        if (adapter.itemCount > 0) {
            binding.logList.scrollToPosition(adapter.itemCount - 1)
        }
        TagLogger.addListener(listener)
    }

    override fun onDestroy() {
        TagLogger.removeListener(listener)
        super.onDestroy()
    }

    private fun shareLogs() {
        val lines = TagLogger.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(filesDir, "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "app-logs-$stamp.txt")
            file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Tag Mobile logs $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_log)))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: getString(R.string.share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
        private val items = mutableListOf<String>()

        fun setAll(lines: List<String>) {
            items.clear()
            items.addAll(lines)
            notifyDataSetChanged()
        }

        fun append(line: String) {
            items.add(line)
            notifyItemInserted(items.size - 1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.setTextIsSelectable(true)
            tv.textSize = 11f
            tv.setTextColor(ContextCompat.getColor(parent.context, R.color.text_strong))
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = items[position]
        }

        override fun getItemCount(): Int = items.size

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
