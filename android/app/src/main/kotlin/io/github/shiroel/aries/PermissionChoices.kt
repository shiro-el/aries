package io.github.shiroel.aries

/** Beacon's live permission projection reuses the checked question-answer API. */
internal const val PERMISSION_TOOL = "permission"
internal const val PERMISSION_ID_PREFIX = "permission:"

internal fun permissionChoiceResource(label: String): Int? = when (label) {
    "Allow" -> R.string.chat_permission_allow
    "Allow always" -> R.string.chat_permission_allow_always
    "Deny" -> R.string.chat_permission_deny
    else -> null
}
