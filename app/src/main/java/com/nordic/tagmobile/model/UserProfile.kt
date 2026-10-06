package com.nordic.tagmobile.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val animalType: String = "Dog",
    val dogName: String = "",
    val breed: String = "",
    val age: String = "",
    val weight: String = "",
    val gender: String = "" // "Male" or "Female"
) {
    val isComplete: Boolean
        get() = animalType.isNotBlank() &&
            dogName.isNotBlank() &&
            breed.isNotBlank() &&
            age.isNotBlank() &&
            weight.isNotBlank() &&
            gender.isNotBlank()
    
    val safeFileName: String get() =
        "${name.replace(Regex("[^A-Za-z0-9]"), "")}_${dogName.replace(Regex("[^A-Za-z0-9]"), "")}"

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("animalType", animalType)
            put("dogName", dogName)
            put("breed", breed)
            put("age", age)
            put("weight", weight)
            put("gender", gender)
        }
    }

    companion object {
        private const val PREF_FILE = "tag_profile_v2"
        private const val KEY_PROFILES = "profiles_json"

        fun fromJson(json: JSONObject): UserProfile {
            return UserProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                animalType = json.optString("animalType", "Dog"),
                dogName = json.optString("dogName", ""),
                breed = json.optString("breed", ""),
                age = json.optString("age", ""),
                weight = json.optString("weight", ""),
                gender = json.optString("gender", "")
            )
        }

        fun loadAll(context: Context): List<UserProfile> {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
            val list = mutableListOf<UserProfile>()
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
            } catch (e: Exception) {
                // Ignore parsing errors
            }
            return list
        }

        fun saveAll(context: Context, profiles: List<UserProfile>) {
            val array = JSONArray()
            profiles.forEach { array.put(it.toJson()) }
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROFILES, array.toString())
                .apply()
        }
    }
}
