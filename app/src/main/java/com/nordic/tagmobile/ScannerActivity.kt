package com.nordic.tagmobile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.ble.TagBleManager
import com.nordic.tagmobile.ble.TagBleScanner
import com.nordic.tagmobile.databinding.ActivityScannerBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.ConnectedDevice
import com.nordic.tagmobile.ui.RssiBarsView

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private val bleManager get() = TagApp.instance.bleManager
    private val bleScanner get() = TagApp.instance.bleScanner
    private val adapter = DeviceAdapter { device, rssi, name -> connectTo(device, rssi, name) }
    private val seen = LinkedHashMap<String, ScanEntry>()
    private var pendingName = "Tag"
    private var pendingRssi = -999

    private data class ScanEntry(
        val device: BluetoothDevice,
        val rssi: Int,
        val displayName: String,
        val isTag: Boolean,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (AppPermissions.ble().all { grants[it] == true }) {
            startScanning()
        } else {
            Toast.makeText(this, R.string.ble_permission_rationale, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val scanListener = object : TagBleScanner.Listener {
        override fun onDevice(device: BluetoothDevice, rssi: Int, name: String, isTag: Boolean) {
            runOnUiThread {
                val prev = seen[device.address]
                val keptName = bestName(prev?.displayName, name)
                val tagged = isTag || prev?.isTag == true ||
                    keptName.equals("Tag", ignoreCase = true) ||
                    keptName.startsWith("Tag_", ignoreCase = true)
                val entry = ScanEntry(device, rssi, keptName, tagged)
                val isNew = prev == null
                val nameChanged = prev != null && prev.displayName != keptName
                val tagChanged = prev != null && prev.isTag != tagged
                seen[device.address] = entry

                if (isNew || nameChanged || tagChanged) {
                    adapter.submit(stableSorted(seen.values))
                } else {
                    adapter.updateRssi(device.address, rssi)
                }
                binding.emptyScanState.visibility = View.GONE
                binding.scanCount.text = getString(R.string.devices_found, seen.size)
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                TagLogger.log(LogCategory.ERRORS, "SCAN_ERROR", message)
                Toast.makeText(this@ScannerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val bleListener = object : TagBleManager.Listener {
        override fun onReady(device: BluetoothDevice) {
            runOnUiThread {
                binding.connectingOverlay.visibility = View.GONE
                TagLogger.log(
                    LogCategory.BLE,
                    "CONNECT_OK",
                    "$pendingName ${device.address} rssi=$pendingRssi",
                )
                TagSession.connectedDevice = ConnectedDevice(
                    name = pendingName.ifBlank { device.name ?: "Tag" },
                    address = device.address,
                    rssi = pendingRssi,
                )
                TagSession.resetRecording()
                // Connect only — return to Home; pet pick happens on Start recording.
                Toast.makeText(
                    this@ScannerActivity,
                    R.string.tag_connected_toast,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                // Dropped during connect (before Device screen) — resume scan.
                if (binding.connectingOverlay.visibility == View.VISIBLE) {
                    binding.connectingOverlay.visibility = View.GONE
                    TagLogger.log(LogCategory.ERRORS, "CONNECT_DROP", pendingName)
                    Toast.makeText(
                        this@ScannerActivity,
                        "Connection dropped — try again",
                        Toast.LENGTH_LONG,
                    ).show()
                    bleScanner.start()
                }
            }
        }

        override fun onPacket(data: ByteArray) = Unit

        override fun onError(message: String) {
            runOnUiThread {
                binding.connectingOverlay.visibility = View.GONE
                TagLogger.log(LogCategory.ERRORS, "CONNECT_FAIL", message)
                Toast.makeText(this@ScannerActivity, message, Toast.LENGTH_LONG).show()
                bleScanner.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backBtn.setOnClickListener { finish() }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.itemAnimator = null
        binding.deviceList.adapter = adapter
        binding.scanCount.text = getString(R.string.devices_found, 0)

        if (!UiFeatureConfig.SHOW_SCAN_BUTTON) {
            finish()
            return
        }

        if (!hasBlePermissions()) {
            permissionLauncher.launch(AppPermissions.ble())
            return
        }
        startScanning()
    }

    private fun startScanning() {
        bleManager.listener = bleListener
        bleScanner.listener = scanListener
        TagLogger.log(
            LogCategory.BLE,
            "SCAN_START",
            "filter=Tag+phone+laptop+TWS (hide other BLE)",
        )
        bleScanner.start()
    }

    private fun hasBlePermissions(): Boolean =
        AppPermissions.ble().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroy() {
        TagLogger.log(LogCategory.BLE, "SCAN_STOP", "devices_seen=${seen.size}")
        bleScanner.stop()
        if (bleManager.listener === bleListener) {
            bleManager.listener = null
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice, rssi: Int, name: String) {
        pendingName = name
        pendingRssi = rssi
        binding.connectingOverlay.visibility = View.VISIBLE
        bleScanner.stop()
        TagLogger.log(LogCategory.BLE, "CONNECT_ATTEMPT", "$name ${device.address}")
        bleManager.connectTag(device)
    }

    companion object {
        private fun bestName(previous: String?, incoming: String): String {
            val next = incoming.trim().ifEmpty { "Unknown" }
            if (previous.isNullOrBlank() || previous == "Unknown") return next
            if (next == "Unknown") return previous
            // Prefer Tag_<serial> over generic Tag when both appear.
            if (previous.equals("Tag", ignoreCase = true) &&
                next.startsWith("Tag_", ignoreCase = true)
            ) {
                return next
            }
            if (next.equals("Tag", ignoreCase = true) &&
                previous.startsWith("Tag_", ignoreCase = true)
            ) {
                return previous
            }
            return next
        }

        /** Tag devices first, then named A–Z, then Unknowns — never by live RSSI. */
        private fun stableSorted(entries: Collection<ScanEntry>): List<ScanEntry> =
            entries.sortedWith(
                compareByDescending<ScanEntry> { it.isTag }
                    .thenBy { it.displayName.equals("Unknown", ignoreCase = true) }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.device.address },
            )
    }

    private class DeviceAdapter(
        private val onClick: (BluetoothDevice, Int, String) -> Unit,
    ) : RecyclerView.Adapter<DeviceAdapter.Holder>() {

        private var items: List<ScanEntry> = emptyList()

        fun submit(list: List<ScanEntry>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = list.size
                override fun areItemsTheSame(o: Int, n: Int) =
                    old[o].device.address == list[n].device.address
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
                override fun getChangePayload(o: Int, n: Int): Any? {
                    if (old[o].rssi != list[n].rssi && old[o].displayName == list[n].displayName) {
                        return PAYLOAD_RSSI
                    }
                    return null
                }
            })
            items = list
            diff.dispatchUpdatesTo(this)
        }

        fun updateRssi(address: String, rssi: Int) {
            val index = items.indexOfFirst { it.device.address == address }
            if (index < 0) return
            val cur = items[index]
            if (cur.rssi == rssi) return
            items = items.toMutableList().also {
                it[index] = cur.copy(rssi = rssi)
            }
            notifyItemChanged(index, PAYLOAD_RSSI)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_scan_device, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            bindFull(holder, items[position])
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_RSSI)) {
                val item = items[position]
                holder.rssi.text = "${item.rssi} dBm"
                holder.bars.setRssi(item.rssi, animate = true)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        private fun bindFull(holder: Holder, item: ScanEntry) {
            holder.name.text = item.displayName
            holder.mac.text = item.device.address
            holder.rssi.text = "${item.rssi} dBm"
            holder.bars.setRssi(item.rssi, animate = false)
            holder.itemView.setOnClickListener {
                onClick(item.device, item.rssi, item.displayName)
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.itemName)
            val mac: TextView = view.findViewById(R.id.itemMac)
            val rssi: TextView = view.findViewById(R.id.itemRssi)
            val bars: RssiBarsView = view.findViewById(R.id.itemRssiBars)
        }

        companion object {
            private const val PAYLOAD_RSSI = "rssi"
        }
    }
}
