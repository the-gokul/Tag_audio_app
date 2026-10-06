package com.nordic.tagmobile

import android.view.View

/**
 * Compile-time UI switches. Flip a value to `false` and rebuild to hide that control.
 * `true` = show / enable. `false` = hide.
 *
     * Home "Start recording" opens MicLiveActivity (ble_2 NUS microphone).
     * Camera DeviceActivity is kept in the project but is not launched from Home.
 */
object UiFeatureConfig {

    // Home: "Scan" CTA when no tag is connected. Opens the BLE scanner.
    const val SHOW_SCAN_BUTTON = true

    // Home: "Start recording" CTA when a tag is already connected. Opens the camera/device screen.
    const val SHOW_START_RECORDING_BUTTON = true

    // Home: hidden BLE scan FAB (layout is gone by default; keep false unless you want the FAB).
    const val SHOW_SCAN_FAB = false

    // Home: Disconnect on the connected-tag card.
    const val SHOW_DISCONNECT_BUTTON = true

    // Home: History shortcut under the main CTA.
    const val SHOW_HOME_HISTORY_BUTTON = true

    // Home: Pets / Profile shortcut under the main CTA.
    const val SHOW_HOME_PETS_BUTTON = true

    // Home: pending cloud-sync banner at the top of the home screen.
    const val SHOW_HOME_SYNC_BANNER = true

    // Home overflow menu → Account.
    const val SHOW_MENU_ACCOUNT = true

    // Home overflow menu → Pets / Profile.
    const val SHOW_MENU_PROFILE = true

    // Home overflow menu → History.
    const val SHOW_MENU_HISTORY = true

    // Home overflow menu → Logs.
    const val SHOW_MENU_LOGS = true

    // Home overflow menu → Settings.
    const val SHOW_MENU_SETTINGS = true

    // Home overflow menu → About.
    const val SHOW_MENU_ABOUT = true

    // Device/camera: red Start/Stop record control (starts camera + tag capture together).
    const val SHOW_CAMERA_RECORD_BUTTON = true

    // Device/camera: flashlight toggle on the preview.
    const val SHOW_FLASH_BUTTON = true

    // Device/camera: front/back camera switch.
    const val SHOW_SWITCH_CAMERA_BUTTON = true

    // Device/camera: History shortcut on the camera overlay (recordings list).
    const val SHOW_DEVICE_RECORDINGS_BUTTON = true

    // Device/camera: overflow menu (disconnect / logs / history / pets).
    const val SHOW_DEVICE_MENU_BUTTON = true

    // Device menu item: Disconnect tag.
    const val SHOW_DEVICE_MENU_DISCONNECT = true

    // Device menu item: Logs.
    const val SHOW_DEVICE_MENU_LOGS = true

    // Device menu item: History.
    const val SHOW_DEVICE_MENU_HISTORY = true

    // Device menu item: Pets / Profile.
    const val SHOW_DEVICE_MENU_PROFILE = true

    // History row: share CSV/XLSX sensor data.
    const val SHOW_HISTORY_DATA_BUTTON = true

    // History row: share / open session video.
    const val SHOW_HISTORY_VIDEO_BUTTON = true

    // History row: session info dialog.
    const val SHOW_HISTORY_INFO_BUTTON = true

    // History row: upload / retry cloud sync for one session.
    const val SHOW_HISTORY_SYNC_BUTTON = true

    // History row: share icon (data + video together).
    const val SHOW_HISTORY_SHARE_BUTTON = true

    // History overflow: enter delete / multi-select mode.
    const val SHOW_HISTORY_DELETE = true

    // History overflow: sync all pending sessions.
    const val SHOW_HISTORY_SYNC_ALL = true

    // Pets screen: add-pet FAB.
    const val SHOW_ADD_PROFILE_BUTTON = true

    // Pets list row: edit pet.
    const val SHOW_EDIT_PROFILE_BUTTON = true

    // Pets list row: delete pet.
    const val SHOW_DELETE_PROFILE_BUTTON = true

    // Custom data screen: write sample/sensor config back to the tag.
    const val SHOW_SAVE_TO_TAG_BUTTON = true

    // Camera settings screen: save resolution / codec / fps.
    const val SHOW_SAVE_CAMERA_CONFIG_BUTTON = true

    fun visible(view: View, enabled: Boolean) {
        view.visibility = if (enabled) View.VISIBLE else View.GONE
    }
}
