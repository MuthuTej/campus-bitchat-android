package com.bitchat.android.messaging.conversation

/**
 * How far an outgoing message has actually got.
 *
 * The states below are what the transport can genuinely report, and no more. In particular there is
 * a real difference between "handed to the network" and "the recipient has it", and on a mesh that
 * gap can last hours — so the two are never collapsed into a single hopeful tick.
 *
 * [PENDING_OFFLINE] is the state that matters most for this product. Elsewhere it would be an
 * error; here it is the ordinary case of messaging someone who is temporarily out of range, and the
 * message is waiting rather than lost. The UI says so in those terms.
 */
enum class DeliveryState {
    /** Being handed to the transport. */
    SENDING,

    /** Accepted by the transport and on its way. Not yet known to have arrived. */
    SENT,

    /** The recipient's device acknowledged it. */
    DELIVERED,

    /** The recipient opened the conversation containing it. */
    READ,

    /** No route now; held and retried when the recipient becomes reachable again. */
    PENDING_OFFLINE,

    /** Could not be sent and will not be retried. */
    FAILED;

    val isTerminal: Boolean get() = this == READ || this == FAILED

    /** True while the message is still expected to make progress on its own. */
    val isInFlight: Boolean get() = this == SENDING || this == SENT || this == PENDING_OFFLINE

    /**
     * How far along this state is, for deciding whether an update is progress or a regression.
     *
     * Declared explicitly rather than taken from the enum's ordinal. Declaration order groups these
     * for readability and is not a ranking — [PENDING_OFFLINE] sits last among the in-flight states
     * but represents *less* progress than [SENT], since nothing has left the device yet. Relying on
     * ordinal here silently prevented a queued message from ever advancing once a route appeared,
     * which is the single most important transition in an offline-first app.
     *
     * [FAILED] has no rank: it is not a point on this scale and is handled as an explicit case.
     */
    val progress: Int
        get() = when (this) {
            SENDING -> 0
            PENDING_OFFLINE -> 1
            SENT -> 2
            DELIVERED -> 3
            READ -> 4
            FAILED -> -1
        }

    /**
     * Short label for the message row.
     *
     * "Waiting to deliver" rather than anything mentioning connectivity: the user does not need to
     * know which transport is unavailable, only that the message is still going to arrive.
     */
    val label: String
        get() = when (this) {
            SENDING -> "Sending"
            SENT -> "Sent"
            DELIVERED -> "Delivered"
            READ -> "Read"
            PENDING_OFFLINE -> "Waiting to deliver"
            FAILED -> "Not sent"
        }
}

/**
 * What the transport did with an outgoing message.
 *
 * Kept separate from [DeliveryState] because it describes one send attempt rather than the
 * message's standing, and because the route taken is a detail the user is never shown.
 */
enum class SendOutcome {
    /** Went out over the local mesh. */
    SENT_LOCAL,

    /** Went out over an internet relay. */
    SENT_REMOTE,

    /** No route right now; the transport is holding it for retry. */
    QUEUED,

    /** Could not be accepted at all. */
    REJECTED;

    fun toDeliveryState(): DeliveryState = when (this) {
        SENT_LOCAL, SENT_REMOTE -> DeliveryState.SENT
        QUEUED -> DeliveryState.PENDING_OFFLINE
        REJECTED -> DeliveryState.FAILED
    }
}
