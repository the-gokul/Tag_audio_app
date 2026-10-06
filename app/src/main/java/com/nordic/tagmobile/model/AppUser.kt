package com.nordic.tagmobile.model

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Local collector identity (login): Name + Phone, no password.
 * Separate from pet profiles ([UserProfile]).
 */
data class AppUser(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val phone: String = "",
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && phone.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("phone", phone)
    }

    companion object {
        private const val PREF_FILE = "tag_app_user_v1"
        private const val KEY_USER = "user_json"

        fun fromJson(json: JSONObject): AppUser = AppUser(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            phone = json.optString("phone", ""),
        )

        fun load(context: Context): AppUser {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_USER, null) ?: return AppUser()
            return try {
                fromJson(JSONObject(raw))
            } catch (_: Exception) {
                AppUser()
            }
        }

        fun save(context: Context, user: AppUser) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER, user.toJson().toString())
                .apply()
        }
    }
}
