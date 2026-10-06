package com.nordic.tagmobile

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nordic.tagmobile.data.local.db.TagDatabase
import com.nordic.tagmobile.databinding.ActivityMainBinding
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingOpenScanner = false

    private val bleUiObserver = BleUiBridge.Observer {
        if (::binding.isInitialized) renderHome()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val bleOk = AppPermissions.ble().all {
            grants[it] != false &&
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (bleOk) {
            TagLogger.log(LogCategory.APP, "PERMISSIONS_GRANTED", "BLE permissions OK")
            if (pendingOpenScanner) ensureReadyAndScan()
        } else {
            pendingOpenScanner = false
            TagLogger.log(LogCategory.ERRORS, "PERMISSIONS_DENIED", "BLE permissions denied")
            Toast.makeText(this, R.string.ble_permission_rationale, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (pendingOpenScanner) ensureReadyAndScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        TagSession.appUser = AppUser.load(this)
        val profiles = UserProfile.loadAll(this)
        if (!TagSession.userProfile.isComplete) {
            TagSession.userProfile = profiles.firstOrNull() ?: UserProfile()
        }

        if (!TagSession.appUser.isComplete) {
            startActivity(
                Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            return
        }
        if (profiles.none { it.isComplete }) {
            startActivity(
                Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_FIRST_RUN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            return
        }

        applyUiFeatureConfig()
        binding.scanFab.setOnClickListener { beginScanFlow() }
        binding.primaryCtaBtn.setOnClickListener {
            if (TagSession.isConnected()) {
                openConnectedDevice()
            } else {
                beginScanFlow()
            }
        }
        binding.quickHistoryBtn.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.quickPetsBtn.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        binding.syncBanner.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.connectedCard.setOnClickListener { openConnectedDevice() }
        binding.disconnectBtn.setOnClickListener {
            TagApp.instance.bleManager.disconnectTag()
            TagSession.clearConnection()
            renderHome()
            Toast.makeText(this, R.string.disconnected, Toast.LENGTH_SHORT).show()
        }
        requestAppPermissionsOnLaunch()
    }

    private fun applyUiFeatureConfig() {
        UiFeatureConfig.visible(binding.scanFab, UiFeatureConfig.SHOW_SCAN_FAB)
        UiFeatureConfig.visible(binding.disconnectBtn, UiFeatureConfig.SHOW_DISCONNECT_BUTTON)
        UiFeatureConfig.visible(binding.quickHistoryBtn, UiFeatureConfig.SHOW_HOME_HISTORY_BUTTON)
        UiFeatureConfig.visible(binding.quickPetsBtn, UiFeatureConfig.SHOW_HOME_PETS_BUTTON)
    }

    private fun openConnectedDevice() {
        if (!UiFeatureConfig.SHOW_START_RECORDING_BUTTON) return
        syncBleUiState()
        if (!TagSession.isConnected()) {
            Toast.makeText(this, R.string.tag_disconnected_toast, Toast.LENGTH_SHORT).show()
            renderHome()
            return
        }
        val tagName = TagSession.connectedDevice?.name?.ifBlank { null } ?: "Tag"
        PetAssignHelper.start(
            activity = this,
            tagName = tagName,
            onAssigned = {
                startActivity(Intent(this, MicLiveActivity::class.java))
            },
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_account)?.isVisible = UiFeatureConfig.SHOW_MENU_ACCOUNT
        menu.findItem(R.id.action_profile)?.isVisible = UiFeatureConfig.SHOW_MENU_PROFILE
        menu.findItem(R.id.action_history)?.isVisible = UiFeatureConfig.SHOW_MENU_HISTORY
        menu.findItem(R.id.action_logs)?.isVisible = UiFeatureConfig.SHOW_MENU_LOGS
        menu.findItem(R.id.action_settings)?.isVisible = UiFeatureConfig.SHOW_MENU_SETTINGS
        menu.findItem(R.id.action_about)?.isVisible = UiFeatureConfig.SHOW_MENU_ABOUT
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_account -> {
                startActivity(
                    Intent(this, LoginActivity::class.java).apply {
                        putExtra(LoginActivity.EXTRA_EDIT, true)
                    },
                )
                true
            }
            R.id.action_profile -> {
                startActivity(Intent(this, ProfileActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_about -> {
                val version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) {
                    "?"
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_message, version))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        BleUiBridge.addObserver(bleUiObserver)
    }

    override fun onStop() {
        BleUiBridge.removeObserver(bleUiObserver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        TagSession.appUser = AppUser.load(this)
        val profiles = UserProfile.loadAll(this)
        if (profiles.none { it.id == TagSession.userProfile.id }) {
            TagSession.userProfile = profiles.firstOrNull() ?: UserProfile()
        }
        syncBleUiState()
        renderHome()
        refreshSyncBanner()
    }

    /** If memory says connected but link is truly dead (not reconnecting), clear Home UI. */
    private fun syncBleUiState() {
        val remembered = TagSession.connectedDevice != null
        val holding = try {
            val ble = TagApp.instance.bleManager
            ble.isTagReady || ble.isHoldingLink
        } catch (_: Exception) {
            false
        }
        if (remembered && !holding) {
            TagLogger.log(LogCategory.BLE, "BLE_STALE_CLEAR", "Home found dead link")
            TagSession.clearConnection()
        }
    }

    private fun requestAppPermissionsOnLaunch() {
        val missing = AppPermissions.all().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.permissions_title)
            .setMessage(R.string.permissions_message)
            .setPositiveButton(R.string.grant_permissions) { _, _ ->
                permissionLauncher.launch(missing.toTypedArray())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginScanFlow() {
        if (!UiFeatureConfig.SHOW_SCAN_BUTTON && !UiFeatureConfig.SHOW_SCAN_FAB) return
        pendingOpenScanner = true
        val missing = AppPermissions.ble().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        ensureReadyAndScan()
    }

    private fun ensureReadyAndScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            Toast.makeText(this, R.string.location_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        pendingOpenScanner = false
        TagLogger.log(LogCategory.BLE, "SCAN_OPEN", "Opening scanner")
        startActivity(Intent(this, ScannerActivity::class.java))
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            true
        }
    }

    private fun renderHome() {
        val device = TagSession.connectedDevice
        val live = TagSession.isConnected()
        if (device == null || !live) {
            binding.emptyState.visibility = View.VISIBLE
            binding.connectedCard.visibility = View.GONE
            binding.primaryCtaBtn.setText(R.string.home_cta_scan)
            UiFeatureConfig.visible(binding.primaryCtaBtn, UiFeatureConfig.SHOW_SCAN_BUTTON)
        } else {
            binding.emptyState.visibility = View.GONE
            binding.connectedCard.visibility = View.VISIBLE
            binding.deviceName.text = device.name
            binding.deviceMac.text = device.address
            binding.deviceRssiBars.setRssi(device.rssi)
            binding.deviceRssi.text = "${device.rssi} dBm"
            binding.primaryCtaBtn.setText(R.string.home_cta_record)
            UiFeatureConfig.visible(binding.primaryCtaBtn, UiFeatureConfig.SHOW_START_RECORDING_BUTTON)
        }
    }

    private fun refreshSyncBanner() {
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching {
                    TagDatabase.get(this@MainActivity).sessionDao().getPendingOrFailed().size
                }.getOrDefault(0)
            }
            if (pending > 0 && UiFeatureConfig.SHOW_HOME_SYNC_BANNER) {
                binding.syncBanner.visibility = View.VISIBLE
                binding.syncBanner.text = getString(R.string.home_sync_pending, pending)
            } else {
                binding.syncBanner.visibility = View.GONE
            }
        }
    }
}
