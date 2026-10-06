package com.nordic.tagmobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityCustomDataBinding
import com.nordic.tagmobile.model.DeviceConfig

class CustomDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cfg = TagSession.deviceConfig
        binding.backBtn.setOnClickListener { finish() }
        binding.samplesPerPacket.setText(cfg.samplesPerPacket.toString())
        binding.flushPkts.setText(cfg.flushPkts.toString())
        binding.samplePeriod.setSelection(
            when (cfg.samplePeriodMs) {
                100 -> 1
                200 -> 2
                else -> 0
            },
        )
        binding.siUnitsToggle.isChecked = TagSession.includeSiUnits
        binding.bmiEnable.isChecked = cfg.bmi270Enabled
        binding.bmeEnable.isChecked = cfg.bme688Enabled
        binding.tmpEnable.isChecked = cfg.tmp117Enabled

        updateSummary()
        binding.samplesPerPacket.setOnFocusChangeListener { _, _ -> updateSummary() }
        binding.flushPkts.setOnFocusChangeListener { _, _ -> updateSummary() }
        binding.samplePeriod.onItemSelectedListener = simpleItemListener { updateSummary() }
        binding.siUnitsToggle.setOnCheckedChangeListener { _, checked ->
            TagSession.includeSiUnits = checked
            updateSummary()
        }

        binding.saveToTagBtn.setOnClickListener {
            readForm()
            binding.saveToTagBtn.isEnabled = false
            binding.saveToTagBtn.text = "Sending…"
            binding.root.postDelayed({
                TagSession.customDataEnabled = true
                Toast.makeText(
                    this,
                    if (TagSession.includeSiUnits) "Settings saved (app CSV: raw + SI)"
                    else "Settings saved (app CSV: raw only)",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }, 800)
        }
        UiFeatureConfig.visible(binding.saveToTagBtn, UiFeatureConfig.SHOW_SAVE_TO_TAG_BUTTON)
    }

    private fun readForm() {
        val samples = binding.samplesPerPacket.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 5
        val flush = binding.flushPkts.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 20
        val period = when (binding.samplePeriod.selectedItemPosition) {
            1 -> 100
            2 -> 200
            else -> 50
        }
        TagSession.deviceConfig = DeviceConfig(
            samplesPerPacket = samples,
            samplePeriodMs = period,
            flushPkts = flush,
            bmi270Enabled = binding.bmiEnable.isChecked,
            bme688Enabled = binding.bmeEnable.isChecked,
            tmp117Enabled = binding.tmpEnable.isChecked,
        )
        binding.samplesPerPacket.setText(samples.toString())
        binding.flushPkts.setText(flush.toString())
    }

    private fun updateSummary() {
        val samples = binding.samplesPerPacket.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 5
        val flush = binding.flushPkts.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: 20
        val period = when (binding.samplePeriod.selectedItemPosition) {
            1 -> 100
            2 -> 200
            else -> 50
        }
        val accum = samples * period
        val pktBytes = 18 + samples * 21 + 1
        binding.configSummary.text =
            "1 packet = $accum ms ($samples samples)\n" +
                "Packet size ≈ $pktBytes B (limit 244 B)\n" +
                "Hold ≈ ${(flush * accum) / 1000.0} s then send $flush packets\n" +
                "Note: tag firmware uses compile-time rates until TAG_CONFIG is added."
    }

    private fun simpleItemListener(onSelect: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) = onSelect()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
}
