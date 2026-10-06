package com.nordic.tagmobile

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivitySettingsBinding
import com.nordic.tagmobile.log.LogLevel
import com.nordic.tagmobile.log.TagLogger

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backBtn.setOnClickListener { finish() }
        binding.loggingEnabled.isChecked = TagLogger.enabled
        binding.loggingEnabled.setOnCheckedChangeListener { _, checked ->
            TagLogger.enabled = checked
        }

        val levels = listOf(
            getString(R.string.log_level_errors),
            getString(R.string.log_level_ble_control),
            getString(R.string.log_level_verbose),
        )
        binding.logLevelSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)
        binding.logLevelSpinner.setSelection(
            when (TagLogger.level) {
                LogLevel.ERRORS_ONLY -> 0
                LogLevel.BLE_CONTROL -> 1
                LogLevel.VERBOSE -> 2
            },
        )
        binding.logLevelSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    TagLogger.level = when (position) {
                        0 -> LogLevel.ERRORS_ONLY
                        2 -> LogLevel.VERBOSE
                        else -> LogLevel.BLE_CONTROL
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
    }
}
