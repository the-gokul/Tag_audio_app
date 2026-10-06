package com.nordic.tagmobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.databinding.ActivityAddProfileBinding
import com.nordic.tagmobile.model.AppUser
import com.nordic.tagmobile.model.UserProfile
import java.util.UUID

class AddProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddProfileBinding
    private var isFirstRun = false
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        editingId = intent.getStringExtra(EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
        binding.backBtn.setOnClickListener { finish() }

        if (editingId != null) {
            binding.toolbarTitle.setText(R.string.edit_profile)
            binding.saveProfileBtn.setText(R.string.save_profile)
            preloadForEdit(editingId!!)
        } else {
            binding.toolbarTitle.setText(R.string.add_profile)
            // First-run onboarding: Continue into app; otherwise Save.
            binding.saveProfileBtn.setText(
                if (isFirstRun) R.string.finish_save else R.string.save_profile,
            )
        }

        binding.saveProfileBtn.setOnClickListener { saveProfile() }

        binding.animalTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.radioCat -> "Cat"
                R.id.radioCattle -> "Cattle"
                else -> "Dog"
            }
            binding.animalNameLabel.text = "$type's Name"
            binding.dogNameInput.hint = "Enter your ${type.lowercase()}'s name"
        }
    }

    private fun preloadForEdit(id: String) {
        val profile = UserProfile.loadAll(this).firstOrNull { it.id == id } ?: run {
            Toast.makeText(this, "Pet not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.dogNameInput.setText(profile.dogName)
        binding.breedInput.setText(profile.breed)
        binding.ageInput.setText(profile.age)
        binding.weightInput.setText(profile.weight)
        when (profile.animalType) {
            "Cat" -> binding.radioCat.isChecked = true
            "Cattle" -> binding.radioCattle.isChecked = true
            else -> binding.radioDog.isChecked = true
        }
        when (profile.gender) {
            "Female" -> binding.radioFemale.isChecked = true
            else -> binding.radioMale.isChecked = true
        }
        binding.animalNameLabel.text = "${profile.animalType}'s Name"
        binding.dogNameInput.hint = "Enter your ${profile.animalType.lowercase()}'s name"
    }

    private fun saveProfile() {
        val dogName = binding.dogNameInput.text.toString().trim()
        val breed = binding.breedInput.text.toString().trim()
        val age = binding.ageInput.text.toString().trim()
        val weight = binding.weightInput.text.toString().trim()
        val isMale = binding.radioMale.isChecked
        val isFemale = binding.radioFemale.isChecked

        val animalType = when (binding.animalTypeGroup.checkedRadioButtonId) {
            R.id.radioCat -> "Cat"
            R.id.radioCattle -> "Cattle"
            else -> "Dog"
        }

        var isValid = true
        if (dogName.isBlank()) { binding.dogNameInput.error = "Required"; isValid = false }
        if (breed.isBlank()) { binding.breedInput.error = "Required"; isValid = false }
        if (age.isBlank()) { binding.ageInput.error = "Required"; isValid = false }
        if (weight.isBlank()) { binding.weightInput.error = "Required"; isValid = false }
        if (!isMale && !isFemale) {
            Toast.makeText(this, "Please select gender", Toast.LENGTH_SHORT).show()
            isValid = false
        }
        if (!isValid) return

        val ownerName = AppUser.load(this).name
        val profiles = UserProfile.loadAll(this).toMutableList()
        val saved = UserProfile(
            id = editingId ?: UUID.randomUUID().toString(),
            name = ownerName,
            animalType = animalType,
            dogName = dogName,
            breed = breed,
            age = age,
            weight = weight,
            gender = if (isMale) "Male" else "Female",
        )

        val editId = editingId
        if (editId != null) {
            val idx = profiles.indexOfFirst { it.id == editId }
            if (idx < 0) {
                Toast.makeText(this, "Pet not found", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            profiles[idx] = saved
            UserProfile.saveAll(this, profiles)
            if (TagSession.userProfile.id == editId) {
                TagSession.userProfile = saved
            }
            Toast.makeText(this, R.string.profile_updated, Toast.LENGTH_SHORT).show()
        } else {
            profiles.add(saved)
            UserProfile.saveAll(this, profiles)
            if (!TagSession.userProfile.isComplete || isFirstRun) {
                TagSession.userProfile = saved
            }
            Toast.makeText(this, "Pet added!", Toast.LENGTH_SHORT).show()
        }
        (application as TagApp).pushPetAsync(saved)
        finish()
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
