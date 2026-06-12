package com.dhairya.newsmemory.capture

/**
 * The capture gate as a pure, testable function (EDD §4.1 rules).
 * Order matters: the allowlist check is FIRST — non-allowlisted content is never
 * inspected further, never logged, never stored.
 */
object CapturePolicy {

    sealed interface Decision {
        data object Capture : Decision
        data object NotAllowlisted : Decision
        data object Ongoing : Decision
        data object GroupSummary : Decision
        data object Blank : Decision
    }

    fun decide(
        packageName: String,
        allowlist: Set<String>,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        title: String?,
        body: String?
    ): Decision = when {
        packageName !in allowlist -> Decision.NotAllowlisted
        isOngoing -> Decision.Ongoing
        isGroupSummary -> Decision.GroupSummary
        title.isNullOrBlank() && body.isNullOrBlank() -> Decision.Blank
        else -> Decision.Capture
    }
}
