package com.nordic.tagmobile

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nordic.tagmobile.model.UserProfile

/**
 * Before opening Record: pick pet if needed, then confirm (2+ pets only).
 */
object PetAssignHelper {

    fun start(
        activity: AppCompatActivity,
        tagName: String,
        onAssigned: () -> Unit,
        onCancel: (() -> Unit)? = null,
    ) {
        val profiles = UserProfile.loadAll(activity).filter { it.isComplete }
        when {
            profiles.isEmpty() -> {
                Toast.makeText(activity, R.string.no_pets_for_tag, Toast.LENGTH_LONG).show()
                onCancel?.invoke()
            }
            profiles.size == 1 -> {
                TagSession.userProfile = profiles.first()
                onAssigned()
            }
            else -> showPetList(activity, profiles, onAssigned, onCancel)
        }
    }

    private fun showPetList(
        activity: AppCompatActivity,
        profiles: List<UserProfile>,
        onAssigned: () -> Unit,
        onCancel: (() -> Unit)?,
    ) {
        val names = profiles.map { it.dogName.ifBlank { "Pet" } }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.select_pet_title)
            .setItems(names) { _, which ->
                if (which in profiles.indices) {
                    showWearConfirm(activity, profiles[which], onAssigned) {
                        showPetList(activity, profiles, onAssigned, onCancel)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onCancel?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    private fun showWearConfirm(
        activity: AppCompatActivity,
        profile: UserProfile,
        onAssigned: () -> Unit,
        onNo: () -> Unit,
    ) {
        val petName = profile.dogName.ifBlank { profile.name }
        AlertDialog.Builder(activity)
            .setTitle(R.string.confirm_pet_wearing_title)
            .setMessage(activity.getString(R.string.confirm_pet_wearing_message, petName))
            .setCancelable(false)
            .setNegativeButton(R.string.confirm_no) { _, _ -> onNo() }
            .setPositiveButton(R.string.confirm_yes) { _, _ ->
                TagSession.userProfile = profile
                onAssigned()
            }
            .show()
    }
}
