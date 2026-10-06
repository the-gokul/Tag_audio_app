package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityLoginBinding
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import com.nordic.tagmobile.util.CountryDialCodes

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var isEditMode = false
    private var dialCode: String = "+91"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isEditMode = intent.getBooleanExtra(EXTRA_EDIT, false)
        val existing = AppUser.load(this)

        dialCode = CountryDialCodes.detectDialCode(this)
        binding.countryCodeText.text = dialCode
        binding.countryCodeText.setOnClickListener { showCountryPicker() }

        // Digits only, max 10 national digits.
        binding.phoneInput.filters = arrayOf(
            InputFilter.LengthFilter(10),
            InputFilter { source, start, end, _, _, _ ->
                var changed = false
                val out = buildString {
                    for (i in start until end) {
                        val c = source[i]
                        if (c.isDigit()) append(c) else changed = true
                    }
                }
                if (!changed) null else out
            },
        )

        if (isEditMode) {
            binding.backBtn.visibility = View.VISIBLE
            binding.backBtn.setOnClickListener { finish() }
            binding.toolbarTitle.setText(R.string.edit_user)
            binding.continueBtn.setText(R.string.save_profile)
            binding.nameInput.setText(existing.name)
            val (code, national) = CountryDialCodes.splitStoredPhone(existing.phone, dialCode)
            dialCode = code
            binding.countryCodeText.text = dialCode
            binding.phoneInput.setText(national.take(10))
        } else {
            binding.backBtn.visibility = View.GONE
            if (!existing.isComplete) {
                val legacyName = UserProfile.loadAll(this).firstOrNull()?.name.orEmpty()
                if (legacyName.isNotBlank()) binding.nameInput.setText(legacyName)
            } else {
                binding.nameInput.setText(existing.name)
                val (code, national) = CountryDialCodes.splitStoredPhone(existing.phone, dialCode)
                dialCode = code
                binding.countryCodeText.text = dialCode
                binding.phoneInput.setText(national.take(10))
            }
        }

        binding.continueBtn.setOnClickListener { saveAndContinue(existing.id) }
    }

    private fun showCountryPicker() {
        val labels = CountryDialCodes.pickerLabels().toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.change_country_code)
            .setItems(labels) { _, which ->
                dialCode = CountryDialCodes.dialCodeFromPickerLabel(labels[which])
                binding.countryCodeText.text = dialCode
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveAndContinue(existingId: String) {
        val name = binding.nameInput.text.toString().trim()
        val national = binding.phoneInput.text.toString().filter { it.isDigit() }
        var ok = true
        if (name.isBlank()) {
            binding.nameInput.error = getString(R.string.required)
            ok = false
        }
        when {
            national.isBlank() -> {
                binding.phoneInput.error = getString(R.string.required)
                ok = false
            }
            national.length != 10 -> {
                binding.phoneInput.error = getString(R.string.phone_must_be_10)
                ok = false
            }
        }
        if (!ok) return

        val fullPhone = "$dialCode$national"
        val user = AppUser(
            id = existingId.ifBlank { java.util.UUID.randomUUID().toString() },
            name = name,
            phone = fullPhone,
        )
        AppUser.save(this, user)
        TagSession.appUser = user

        if (isEditMode) {
            Toast.makeText(this, R.string.user_saved, Toast.LENGTH_SHORT).show()
            (application as TagApp).pushUserAsync(user)
            finish()
            return
        }

        (application as TagApp).pushUserAsync(user)

        val pets = UserProfile.loadAll(this)
        if (pets.isEmpty()) {
            startActivity(
                Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_FIRST_RUN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_EDIT = "edit_user"
    }
}
