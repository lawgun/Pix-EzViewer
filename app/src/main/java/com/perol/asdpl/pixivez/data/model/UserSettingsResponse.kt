package com.perol.asdpl.pixivez.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GET /v1/user/ai-show-settings
@Serializable
class UserAISettingsResponse(
    @SerialName("show_ai")
    val show_ai: Boolean = true
)

// GET /v1/user/restricted-mode-settings
@Serializable
class RestrictedModeSettingsResponse(
    @SerialName("is_restricted_mode_enabled")
    val is_restricted_mode_enabled: Boolean = false
)
