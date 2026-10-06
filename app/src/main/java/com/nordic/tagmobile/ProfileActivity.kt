package com.nordic.tagmobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nordic.tagmobile.databinding.ActivityProfileBinding
import com.nordic.tagmobile.model.UserProfile

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var isFirstRun = false
    private var openedAddOnce = false
    private val profiles = mutableListOf<UserProfile>()
    private lateinit var adapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isFirstRun = intent.getBooleanExtra(EXTRA_FIRST_RUN, false)
        binding.backBtn.visibility = if (isFirstRun) View.INVISIBLE else View.VISIBLE
        binding.backBtn.setOnClickListener { finish() }
        binding.saveProfileBtn.visibility = if (isFirstRun) View.VISIBLE else View.GONE
        // Pets screen is pet-only — account lives under Menu → Account.
        binding.userAvatarBtn.visibility = View.GONE

        adapter = ProfileAdapter(
            profiles,
            onEdit = { profile ->
                startActivity(
                    Intent(this, AddProfileActivity::class.java).apply {
                        putExtra(AddProfileActivity.EXTRA_PROFILE_ID, profile.id)
                    },
                )
            },
            onDelete = { profile ->
                profiles.remove(profile)
                UserProfile.saveAll(this, profiles)
                if (TagSession.userProfile.id == profile.id) {
                    TagSession.userProfile = profiles.firstOrNull() ?: UserProfile()
                }
                (application as TagApp).deletePetIndexAsync(profile.id)
                refreshList()
            },
        )
        binding.profilesList.layoutManager = LinearLayoutManager(this)
        binding.profilesList.adapter = adapter

        binding.addProfileBtn.setOnClickListener {
            startActivity(
                Intent(this, AddProfileActivity::class.java).apply {
                    if (isFirstRun) putExtra(AddProfileActivity.EXTRA_FIRST_RUN, true)
                },
            )
        }
        UiFeatureConfig.visible(binding.addProfileRow, UiFeatureConfig.SHOW_ADD_PROFILE_BUTTON)

        binding.saveProfileBtn.setOnClickListener {
            if (profiles.isEmpty()) {
                Toast.makeText(this, "Please add at least one pet", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AddProfileActivity::class.java).apply {
                    putExtra(AddProfileActivity.EXTRA_FIRST_RUN, true)
                })
                return@setOnClickListener
            }
            if (!TagSession.userProfile.isComplete) {
                TagSession.userProfile = profiles.first()
            }
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
            )
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        profiles.clear()
        profiles.addAll(UserProfile.loadAll(this))
        if (!TagSession.userProfile.isComplete && profiles.isNotEmpty()) {
            TagSession.userProfile = profiles.first()
        }
        refreshList()
        if (isFirstRun && profiles.isEmpty() && !openedAddOnce) {
            openedAddOnce = true
            startActivity(
                Intent(this, AddProfileActivity::class.java).apply {
                    putExtra(AddProfileActivity.EXTRA_FIRST_RUN, true)
                },
            )
        }
    }

    private fun refreshList() {
        adapter.notifyDataSetChanged()
        val empty = profiles.isEmpty()
        binding.emptyProfiles.visibility = if (empty) View.VISIBLE else View.GONE
        binding.profilesList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
    }
}

class ProfileAdapter(
    private val items: List<UserProfile>,
    private val onEdit: (UserProfile) -> Unit,
    private val onDelete: (UserProfile) -> Unit,
) : RecyclerView.Adapter<ProfileAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.dogName
        holder.details.text =
            "${item.animalType} | ${item.breed} | ${item.gender} | ${item.age}y | ${item.weight}kg"
        holder.editBtn.setOnClickListener { onEdit(item) }
        holder.deleteBtn.setOnClickListener { onDelete(item) }
        UiFeatureConfig.visible(holder.editBtn, UiFeatureConfig.SHOW_EDIT_PROFILE_BUTTON)
        UiFeatureConfig.visible(holder.deleteBtn, UiFeatureConfig.SHOW_DELETE_PROFILE_BUTTON)
    }

    override fun getItemCount() = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.profileName)
        val details: TextView = view.findViewById(R.id.profileDetails)
        val editBtn: ImageButton = view.findViewById(R.id.editProfileBtn)
        val deleteBtn: ImageButton = view.findViewById(R.id.deleteProfileBtn)
    }
}
