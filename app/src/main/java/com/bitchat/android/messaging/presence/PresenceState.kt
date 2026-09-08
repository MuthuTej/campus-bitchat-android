package com.bitchat.android.messaging.presence

/**
 * Whether someone can currently be reached.
 *
 * This is not "are they online" in the internet sense, and the app must not pretend otherwise. The
 * only question this layer can actually answer is whether a route to that person exists through the
 * transports available right now, which is a different and more honest claim: a peer two rooms away
 * with no internet at all is reachable, and a peer with excellent wifi on another continent is not.
 *
 * Every value here is derived live from transport state. None of it is persisted — a reachability
 * value read back from disk describes the past, and showing a stale "available" is worse than
 * showing nothing at all.
 */
enum class PresenceState {
    /** A direct link to this person exists and a secure session is established. */
    DIRECTLY_REACHABLE,

    /** Reachable, but only by relaying through other devices. Slower and less certain. */
    MESH_REACHABLE,

    /** Seen recently enough to be worth attempting, but not currently routable. */
    RECENTLY_SEEN,

    /** Known, with no current route. Messages are queued rather than refused. */
    OFFLINE,

    /** Never seen, or seen too long ago for any statement to be meaningful. */
    UNKNOWN;

    /** True when a message can be expected to move now rather than waiting in the outbox. */
    val isReachableNow: Boolean
        get() = this == DIRECTLY_REACHABLE || this == MESH_REACHABLE

    /**
     * Short, non-technical label.
     *
     * Deliberately avoids "online": the user is not being told about internet connectivity, and
     * borrowing that word would promise something this app cannot verify.
     */
    val label: String
        get() = when (this) {
            DIRECTLY_REACHABLE -> "Available"
            MESH_REACHABLE -> "Reachable through nearby devices"
            RECENTLY_SEEN -> "Seen recently"
            OFFLINE -> "Currently unavailable"
            UNKNOWN -> ""
        }
}
