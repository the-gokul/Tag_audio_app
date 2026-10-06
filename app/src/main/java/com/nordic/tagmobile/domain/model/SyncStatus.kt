package com.nordic.tagmobile.domain.model

/** Local cloud sync lifecycle for a session package. */
enum class SyncStatus {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
    /** Microphone WAV is kept on the phone; not uploaded. */
    LOCAL_ONLY,
    ;

    companion object {
        fun fromRaw(raw: String?): SyncStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}
