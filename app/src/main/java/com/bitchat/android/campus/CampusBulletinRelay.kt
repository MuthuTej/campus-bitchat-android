package com.bitchat.android.campus

/**
 * Decides when a phone that already holds bulletins should offer them to someone new.
 *
 * The mesh store-and-forward cache skips public broadcasts, and its gossip window is short and
 * in-memory. Late pickup of a bulletin therefore has to be explicit: when a new peer appears,
 * re-send every bulletin this device still considers valid. Peers that were already offered the
 * current set are not sent another copy until the set of neighbours changes.
 */
class CampusBulletinRelay {

    private var offeredPeers: Set<String> = emptySet()

    /**
     * @param currentPeers peer ids currently nearby
     * @param retainedPayloads bulletin envelopes and credential cards worth offering
     * @return the payloads to broadcast now, or an empty list
     */
    fun onPeers(currentPeers: Set<String>, retainedPayloads: List<String>): List<String> {
        if (currentPeers.isEmpty()) {
            offeredPeers = emptySet()
            return emptyList()
        }
        val joined = currentPeers - offeredPeers
        if (retainedPayloads.isEmpty() || joined.isEmpty()) {
            if (retainedPayloads.isNotEmpty()) offeredPeers = currentPeers
            return emptyList()
        }
        offeredPeers = currentPeers
        return retainedPayloads
    }
}
