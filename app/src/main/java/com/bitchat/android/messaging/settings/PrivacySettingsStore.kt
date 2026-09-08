package com.bitchat.android.messaging.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's privacy choices.
 *
 * Reads apply immediately through [settings], so turning a protection on takes effect for the very
 * next decision rather than at the next restart — a privacy control that needed the app relaunched
 * to bite would be one the user could not trust in the moment they reached for it.
 */
class PrivacySettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<PrivacySettings> = _settings.asStateFlow()

    fun update(transform: (PrivacySettings) -> PrivacySettings): PrivacySettings {
        val updated = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_MESSAGES_FROM_UNKNOWN, updated.allowMessagesFromUnknown)
            .putBoolean(KEY_GROUP_INVITATIONS, updated.allowGroupInvitations)
            .putBoolean(KEY_FILE_TRANSFERS, updated.allowFileTransfers)
            .putBoolean(KEY_SHARE_PHONE, updated.sharePhoneNumberWithContacts)
            .apply()
        _settings.value = updated
        return updated
    }

    private fun load(): PrivacySettings {
        val defaults = PrivacySettings.DEFAULT
        return PrivacySettings(
            allowMessagesFromUnknown = prefs.getBoolean(
                KEY_MESSAGES_FROM_UNKNOWN, defaults.allowMessagesFromUnknown
            ),
            allowGroupInvitations = prefs.getBoolean(
                KEY_GROUP_INVITATIONS, defaults.allowGroupInvitations
            ),
            allowFileTransfers = prefs.getBoolean(
                KEY_FILE_TRANSFERS, defaults.allowFileTransfers
            ),
            sharePhoneNumberWithContacts = prefs.getBoolean(
                KEY_SHARE_PHONE, defaults.sharePhoneNumberWithContacts
            )
        )
    }

    private companion object {
        const val PREFS_NAME = "messaging_privacy_settings"
        const val KEY_MESSAGES_FROM_UNKNOWN = "allow_messages_from_unknown"
        const val KEY_GROUP_INVITATIONS = "allow_group_invitations"
        const val KEY_FILE_TRANSFERS = "allow_file_transfers"
        const val KEY_SHARE_PHONE = "share_phone_number"
    }
}
