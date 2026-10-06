package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nordic.tagmobile.data.cloud.UploadProgress
import com.nordic.tagmobile.data.local.SessionIndexer
import com.nordic.tagmobile.databinding.ActivityHistoryBinding
import com.nordic.tagmobile.domain.model.SyncStatus
import com.nordic.tagmobile.storage.HistoryEntry
import com.nordic.tagmobile.storage.RecordingStore
import com.nordic.tagmobile.work.UploadSessionWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var syncUi: Map<String, SessionIndexer.SyncUiInfo> = emptyMap()
    /** Session ids with WorkManager upload currently ENQUEUED/RUNNING. */
    private var activeUploadIds: Set<String> = emptySet()
    private var syncAllRunning = false
    /** Live byte progress of uploads running right now (from UploadProgress). */
    private var liveProgress: Map<String, UploadProgress.Snapshot> = emptyMap()

    private val adapter = HistoryAdapter(
        onData = {
            if (it.dataFile.exists()) {
                val mime = if (it.dataFile.name.endsWith(".xlsx"))
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else "text/csv"
                shareFiles(listOf(it.dataFile), mime)
            } else Toast.makeText(this, "Data file missing", Toast.LENGTH_SHORT).show()
        },
        onVideo = {
            when {
                it.audioFile?.exists() == true -> shareFiles(listOf(it.audioFile), "audio/wav")
                it.videoFile?.exists() == true -> shareFiles(listOf(it.videoFile), "video/*")
                else -> Toast.makeText(this, "Media file missing", Toast.LENGTH_SHORT).show()
            }
        },
        onInfo = {
            val info = syncUi[it.baseName]
            val sync = effectiveStatus(it.baseName, info?.status)
            val err = info?.lastError?.let { e -> "\nError: $e" }.orEmpty()
            AlertDialog.Builder(this)
                .setTitle("Session Info")
                .setMessage(
                    "Session: ${it.baseName}\nStatus: ${it.status}\nCloud: $sync$err\nPackets: ${it.packetCount}\n" +
                        "Samples: ${it.sampleCount}\nData: ${if (it.dataFile.exists()) "Yes" else "No"}\n" +
                        "Video: ${if (it.videoFile?.exists() == true) "Yes" else "No"}\n" +
                        "Audio: ${if (it.audioFile?.exists() == true) "Yes" else "No"}\n" +
                        "Folder: ${it.sessionDir?.name ?: "legacy (flat files)"}",
                )
                .setPositiveButton("OK", null)
                .show()
        },
        onSync = { item -> handleSingleSync(item) },
        onShare = { item ->
            val files = buildList {
                if (item.dataFile.exists()) add(item.dataFile)
                item.videoFile?.takeIf { it.exists() }?.let { add(it) }
                item.audioFile?.takeIf { it.exists() }?.let { add(it) }
            }
            if (files.isEmpty()) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            } else {
                shareFiles(files, "*/*")
            }
        },
        isSelectionMode = { selectionMode },
        isSelected = { selected.contains(it.baseName) },
        onToggleSelect = { item, checked ->
            if (checked) selected.add(item.baseName) else selected.remove(item.baseName)
            updateDeleteHint()
        },
        syncInfoOf = { syncUi[it.baseName] },
        effectiveStatusOf = { id, status -> effectiveStatus(id, status) },
        progressOf = { liveProgress[it] },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backBtn.setOnClickListener {
            if (selectionMode) exitSelectionMode() else finish()
        }
        binding.menuBtn.setOnClickListener { showMenu(it) }
        UiFeatureConfig.visible(
            binding.menuBtn,
            UiFeatureConfig.SHOW_HISTORY_DELETE || UiFeatureConfig.SHOW_HISTORY_SYNC_ALL,
        )
        binding.cancelDeleteBtn.setOnClickListener { exitSelectionMode() }
        binding.confirmDeleteBtn.setOnClickListener { confirmPermanentDelete() }
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = adapter
        maybeRequestNotificationPermission()
        observeUploadWork()
        observeUploadProgress()
        reload()
    }

    /** Redraws the uploading row on every progress report; reloads when an upload ends. */
    private fun observeUploadProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UploadProgress.state.collect { now ->
                    val before = liveProgress
                    liveProgress = now
                    (before.keys + now.keys).forEach { id ->
                        if (before[id] != now[id]) adapter.notifySession(id)
                    }
                    if ((before.keys - now.keys).isNotEmpty()) reload()
                }
            }
        }
    }

    private fun observeUploadWork() {
        val wm = WorkManager.getInstance(this)
        wm.getWorkInfosByTagLiveData(UploadSessionWorker::class.java.name)
            .observe(this) { infos ->
                if (infos.any {
                        it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.SUCCEEDED ||
                            it.state == WorkInfo.State.FAILED ||
                            it.state == WorkInfo.State.CANCELLED
                    }
                ) {
                    reload()
                }
            }
        wm.getWorkInfosForUniqueWorkLiveData(UploadSessionWorker.UNIQUE_ALL)
            .observe(this) { infos ->
                syncAllRunning = infos.any {
                    it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (infos.any {
                        it.state == WorkInfo.State.SUCCEEDED ||
                            it.state == WorkInfo.State.FAILED ||
                            it.state == WorkInfo.State.CANCELLED ||
                            it.state == WorkInfo.State.RUNNING
                    }
                ) {
                    reload()
                }
            }
    }

    private fun effectiveStatus(sessionId: String, stored: SyncStatus?): SyncStatus {
        if (sessionId in activeUploadIds || stored == SyncStatus.UPLOADING) {
            return SyncStatus.UPLOADING
        }
        return stored ?: SyncStatus.PENDING
    }

    private fun handleSingleSync(item: HistoryEntry) {
        val status = effectiveStatus(item.baseName, syncUi[item.baseName]?.status)
        when (status) {
            SyncStatus.SYNCED -> {
                Toast.makeText(this, R.string.sync_already_done, Toast.LENGTH_SHORT).show()
            }
            SyncStatus.UPLOADING -> {
                // Allow force-retry when UI shows UPLOADING but nothing is actively tracked
                // (stuck state from older builds / hung OkHttp on Android 9).
                if (item.baseName in activeUploadIds) {
                    Toast.makeText(this, R.string.sync_in_progress, Toast.LENGTH_SHORT).show()
                } else {
                    startDirectOrQueuedSync(item)
                }
            }
            SyncStatus.PENDING, SyncStatus.FAILED -> {
                startDirectOrQueuedSync(item)
            }
            SyncStatus.LOCAL_ONLY -> {
                Toast.makeText(this, R.string.after_stop_mic_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDirectOrQueuedSync(item: HistoryEntry) {
        lifecycleScope.launch {
            SessionIndexer.upsertFromHistoryEntry(this@HistoryActivity, item)
            activeUploadIds = activeUploadIds + item.baseName
            (application as TagApp).syncSessionFromUi(item.baseName)
            Toast.makeText(
                this@HistoryActivity,
                getString(R.string.sync_started, item.baseName),
                Toast.LENGTH_SHORT,
            ).show()
            reload()
        }
    }

    private fun handleSyncAll() {
        lifecycleScope.launch {
            syncUi = SessionIndexer.syncUiMap(this@HistoryActivity)
            if (syncAllRunning) {
                Toast.makeText(this@HistoryActivity, R.string.sync_all_in_progress, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = RecordingStore.listHistory(this@HistoryActivity)
            val needSync = items.count { entry ->
                val s = effectiveStatus(entry.baseName, syncUi[entry.baseName]?.status)
                s == SyncStatus.PENDING || s == SyncStatus.FAILED ||
                    (s == SyncStatus.UPLOADING && entry.baseName !in activeUploadIds)
            }
            if (needSync == 0) {
                Toast.makeText(this@HistoryActivity, R.string.sync_all_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            syncAllRunning = true
            (application as TagApp).syncAllFromUi()
            Toast.makeText(
                this@HistoryActivity,
                getString(R.string.sync_all_started, needSync),
                Toast.LENGTH_SHORT,
            ).show()
            reload()
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(permission), 4401)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (UiFeatureConfig.SHOW_HISTORY_DELETE) {
            popup.menu.add(0, 1, 0, getString(R.string.delete))
        }
        if (UiFeatureConfig.SHOW_HISTORY_SYNC_ALL) {
            popup.menu.add(0, 2, 1, getString(R.string.sync_all_pending))
        }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    enterSelectionMode()
                    true
                }
                2 -> {
                    handleSyncAll()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun enterSelectionMode() {
        selectionMode = true
        selected.clear()
        binding.deleteBar.visibility = View.VISIBLE
        updateDeleteHint()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        binding.deleteBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun updateDeleteHint() {
        binding.deleteHint.text = getString(R.string.selected_count, selected.size)
    }

    private fun confirmPermanentDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_to_delete, Toast.LENGTH_SHORT).show()
            return
        }
        val unsynced = selected.count { id ->
            effectiveStatus(id, syncUi[id]?.status) != SyncStatus.SYNCED
        }
        val message = when {
            unsynced > 0 -> getString(R.string.delete_unsynced_warning, unsynced, selected.size)
            else -> getString(R.string.delete_synced_ok, selected.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_permanently_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                val items = RecordingStore.listHistory(this).filter { selected.contains(it.baseName) }
                items.forEach { RecordingStore.deleteEntry(this, it) }
                Toast.makeText(this, R.string.deleted_permanently, Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reload() {
        lifecycleScope.launch {
            syncUi = SessionIndexer.syncUiMap(this@HistoryActivity)
            val items = RecordingStore.listHistory(this@HistoryActivity)
            val (running, allRunning) = withContext(Dispatchers.IO) {
                val wm = WorkManager.getInstance(this@HistoryActivity)
                val run = mutableSetOf<String>()
                for (item in items) {
                    if (syncUi[item.baseName]?.status == SyncStatus.UPLOADING) {
                        run.add(item.baseName)
                    }
                    val works = wm.getWorkInfosForUniqueWork("upload-session-${item.baseName}").get()
                    if (works.any {
                            it.state == WorkInfo.State.ENQUEUED ||
                                it.state == WorkInfo.State.RUNNING ||
                                it.state == WorkInfo.State.BLOCKED
                        }
                    ) {
                        run.add(item.baseName)
                    }
                }
                val syncAll = wm.getWorkInfosForUniqueWork(UploadSessionWorker.UNIQUE_ALL).get()
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
                run to syncAll
            }
            activeUploadIds = running
            syncAllRunning = allRunning
            adapter.submit(items)
            binding.emptyHistory.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun shareFiles(files: List<java.io.File>, type: String) {
        val uris = ArrayList<android.net.Uri>()
        files.forEach { file ->
            if (!file.exists()) return@forEach
            uris.add(
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file,
                ),
            )
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private class HistoryAdapter(
        private val onData: (HistoryEntry) -> Unit,
        private val onVideo: (HistoryEntry) -> Unit,
        private val onInfo: (HistoryEntry) -> Unit,
        private val onSync: (HistoryEntry) -> Unit,
        private val onShare: (HistoryEntry) -> Unit,
        private val isSelectionMode: () -> Boolean,
        private val isSelected: (HistoryEntry) -> Boolean,
        private val onToggleSelect: (HistoryEntry, Boolean) -> Unit,
        private val syncInfoOf: (HistoryEntry) -> SessionIndexer.SyncUiInfo?,
        private val effectiveStatusOf: (String, SyncStatus?) -> SyncStatus,
        private val progressOf: (String) -> UploadProgress.Snapshot?,
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<HistoryEntry> = emptyList()
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        fun submit(list: List<HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        fun notifySession(sessionId: String) {
            val index = items.indexOfFirst { it.baseName == sessionId }
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val info = syncInfoOf(item)
            val sync = effectiveStatusOf(item.baseName, info?.status)
            holder.title.text = item.baseName
            holder.meta.text =
                "${dateFmt.format(Date(item.savedAtMs))} · " +
                    "Packets: ${item.packetCount} · Samples: ${item.sampleCount}"
            val cloudLine = when {
                sync == SyncStatus.FAILED && !info?.lastError.isNullOrBlank() ->
                    "Cloud: FAILED — ${info!!.lastError!!.take(60)}"
                else -> "Cloud: ${sync.name}"
            }
            holder.status.text = "${item.status} · $cloudLine"
            val live = progressOf(item.baseName)
            if (live != null) {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = live.phase == UploadProgress.Phase.PREPARING
                if (live.phase == UploadProgress.Phase.UPLOADING) holder.progressBar.setProgressCompat(live.percent, true)
                holder.progressText.text = live.describe()
            } else {
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
            }
            holder.dataBtn.setOnClickListener { onData(item) }
            holder.videoBtn.setOnClickListener { onVideo(item) }
            holder.infoBtn.setOnClickListener { onInfo(item) }
            holder.syncBtn.setOnClickListener { onSync(item) }
            holder.shareBtn.setOnClickListener { onShare(item) }
            val ctx = holder.itemView.context
            val hasXlsx = item.dataFile.exists()
            val hasMedia = item.audioFile?.exists() == true || item.videoFile?.exists() == true
            UiFeatureConfig.visible(holder.dataBtn, UiFeatureConfig.SHOW_HISTORY_DATA_BUTTON && hasXlsx)
            UiFeatureConfig.visible(holder.videoBtn, UiFeatureConfig.SHOW_HISTORY_VIDEO_BUTTON && hasMedia)
            UiFeatureConfig.visible(holder.infoBtn, UiFeatureConfig.SHOW_HISTORY_INFO_BUTTON)
            UiFeatureConfig.visible(
                holder.syncBtn,
                UiFeatureConfig.SHOW_HISTORY_SYNC_BUTTON && hasXlsx,
            )
            holder.videoBtn.text = if (item.audioFile?.exists() == true) {
                ctx.getString(R.string.audio)
            } else {
                ctx.getString(R.string.video)
            }

            when (sync) {
                SyncStatus.SYNCED -> {
                    holder.syncBtn.text = ctx.getString(R.string.synced)
                    holder.syncBtn.isEnabled = true
                    holder.syncBtn.alpha = 0.55f
                }
                SyncStatus.UPLOADING -> {
                    holder.syncBtn.text = ctx.getString(R.string.syncing)
                    holder.syncBtn.isEnabled = true
                    holder.syncBtn.alpha = 0.7f
                }
                SyncStatus.FAILED -> {
                    holder.syncBtn.text = ctx.getString(R.string.sync_retry)
                    holder.syncBtn.isEnabled = true
                    holder.syncBtn.alpha = 1f
                }
                SyncStatus.PENDING -> {
                    holder.syncBtn.text = ctx.getString(R.string.sync)
                    holder.syncBtn.isEnabled = true
                    holder.syncBtn.alpha = 1f
                }
                SyncStatus.LOCAL_ONLY -> {
                    holder.syncBtn.text = ctx.getString(R.string.audio)
                    holder.syncBtn.isEnabled = false
                    holder.syncBtn.alpha = 0.45f
                }
            }

            val selecting = isSelectionMode()
            holder.shareBtn.visibility = if (selecting || !UiFeatureConfig.SHOW_HISTORY_SHARE_BUTTON) {
                View.GONE
            } else {
                View.VISIBLE
            }
            holder.selectCheck.visibility = if (selecting) View.VISIBLE else View.GONE
            holder.selectCheck.setOnCheckedChangeListener(null)
            holder.selectCheck.isChecked = isSelected(item)
            holder.selectCheck.setOnCheckedChangeListener { _, checked ->
                onToggleSelect(item, checked)
            }
            holder.itemView.setOnClickListener {
                if (selecting) {
                    holder.selectCheck.isChecked = !holder.selectCheck.isChecked
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.itemTitle)
            val meta: TextView = view.findViewById(R.id.itemMeta)
            val status: TextView = view.findViewById(R.id.itemStatus)
            val dataBtn: Button = view.findViewById(R.id.dataBtn)
            val videoBtn: Button = view.findViewById(R.id.videoBtn)
            val infoBtn: Button = view.findViewById(R.id.infoBtn)
            val syncBtn: Button = view.findViewById(R.id.syncBtn)
            val shareBtn: ImageButton = view.findViewById(R.id.shareBtn)
            val selectCheck: CheckBox = view.findViewById(R.id.selectCheck)
            val progressBar: LinearProgressIndicator = view.findViewById(R.id.uploadProgress)
            val progressText: TextView = view.findViewById(R.id.uploadProgressText)
        }
    }
}
