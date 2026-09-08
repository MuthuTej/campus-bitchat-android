package com.bitchat.android.campus

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presentation state for the campus overlay.
 *
 * Owns no networking, no cryptographic primitives and no persistence of its own. Mesh transport,
 * Noise sessions, channel encryption and the foreground service all remain exactly as upstream; this
 * class only decides what a non-technical user sees and silently performs the channel mechanics they
 * would otherwise have to type.
 */
class CampusViewModel(
    application: Application,
    private val chatViewModel: ChatViewModel
) : ViewModel() {

    private val appContext = application.applicationContext
    private val identityStore = SecureIdentityStateManager(appContext)
    private val alertSigner = CampusAlertSigner(appContext)
    private val receivedFiles = CampusReceivedFileStore(appContext)

    val identityRegistry = CampusIdentityRegistry()

    /**
     * Transport binding for file transfers.
     *
     * Every envelope is addressed to one peer and sent down the existing private-message path, so it
     * inherits that path's Noise session encryption, routing and retry. It deliberately does NOT go
     * through [ChatViewModel.sendMessage], which targets whatever conversation the user currently
     * has open — that made file chunks broadcast into the class channel, where every member
     * reassembled the document and answered with their own acknowledgements.
     */
    val fileTransfers = CampusFileTransferService(
        transport = ::sendFileEnvelope
    )

    /** Message IDs already fed to the transfer engine, so a re-emitted chat list is not replayed. */
    private val consumedFileEnvelopes: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Hand one addressed file envelope to the private-message router.
     *
     * Returns false — parking the transfer rather than failing it — whenever the peer has no
     * established session. A chunk offered while the peer is unreachable would be enqueued in the
     * router's shared per-peer outbox, which is bounded and evicts oldest-first, so a multi-chunk
     * document would push the user's actual messages out of it. Parking instead lets
     * [CampusFileTransferService.resumePausedTransfers] restart from the cumulative ACK when the
     * peer returns, and any duplicate chunk that results is already handled by the receiver.
     */
    private fun sendFileEnvelope(recipient: String, envelope: String): Boolean {
        val mesh = chatViewModel.meshServiceFacade
        if (!mesh.hasEstablishedSession(recipient)) return false
        val nickname = chatViewModel.peerNicknames.value[recipient] ?: recipient
        val route = com.bitchat.android.services.MessageRouter
            .getInstance(appContext, mesh)
            .sendPrivate(envelope, recipient, nickname, java.util.UUID.randomUUID().toString())
        return route == com.bitchat.android.services.MessageRouter.RouteResult.MESH ||
            route == com.bitchat.android.services.MessageRouter.RouteResult.NOSTR
    }

    data class ClassRow(
        val definition: CampusConfig.CampusClass,
        val unreadCount: Int,
        val lastMessagePreview: String?,
        val enrolled: Boolean
    )

    data class DisplayedAlert(
        val id: String,
        val title: String,
        val body: String,
        val publisherName: String,
        val publisherRole: CampusRole,
        val timestampMillis: Long
    )

    private val _selectedTab = MutableStateFlow(CampusTab.CLASSES)
    val selectedTab: StateFlow<CampusTab> = _selectedTab

    private val _openClass = MutableStateFlow<CampusConfig.CampusClass?>(null)
    val openClass: StateFlow<CampusConfig.CampusClass?> = _openClass

    private val _showAlertComposer = MutableStateFlow(false)
    val showAlertComposer: StateFlow<Boolean> = _showAlertComposer

    private val _transferProgress = MutableStateFlow<List<CampusFileTransferService.Progress>>(emptyList())
    val transferProgress: StateFlow<List<CampusFileTransferService.Progress>> = _transferProgress

    private val _rejectedAlertCount = MutableStateFlow(0)
    val rejectedAlertCount: StateFlow<Int> = _rejectedAlertCount

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    /**
     * This device's own campus identity.
     *
     * Determined by verifying an institution-issued credential naming this device's signing key —
     * never by local assertion. With no credential provisioned the local user is a plain unverified
     * peer, which in particular means the alert composer stays hidden.
     */
    private val _localIdentity = MutableStateFlow<CampusIdentityRegistry.Identity>(
        CampusIdentityRegistry.Identity.Unverified(null)
    )
    val localIdentity: StateFlow<CampusIdentityRegistry.Identity> = _localIdentity

    val localRole: StateFlow<CampusRole?> = _localIdentity
        .map { (it as? CampusIdentityRegistry.Identity.Verified)?.role }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val canPublishAlerts: StateFlow<Boolean> = localRole
        .map { it?.canPublishAlerts == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val classRows: StateFlow<List<ClassRow>> = combine(
        chatViewModel.channelMessages,
        chatViewModel.unreadChannelMessages,
        chatViewModel.joinedChannels
    ) { messages, unread, joined ->
        CampusConfig.classes.map { definition ->
            val tag = definition.channelTag
            ClassRow(
                definition = definition,
                unreadCount = unread[tag] ?: 0,
                lastMessagePreview = messages[tag]
                    ?.lastOrNull { !isProtocolEnvelope(it.content) }
                    ?.let { "${displayNameFor(it)}: ${it.content}" },
                enrolled = joined.contains(tag)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Verified alerts only.
     *
     * Anything that fails any link of the trust chain is counted and dropped. It is never downgraded
     * to a plain chat message, because an unverified string that reads "class cancelled" is exactly
     * the payload this whole mechanism exists to suppress.
     */
    val alerts: StateFlow<List<DisplayedAlert>> = chatViewModel.channelMessages
        .map { channelMessages ->
            val raw = channelMessages[CampusConfig.ALERTS_CHANNEL].orEmpty()
            var rejected = 0
            val verified = raw.mapNotNull { message ->
                if (!CampusAlert.looksLikeAlert(message.content)) return@mapNotNull null
                when (val result = CampusAlertVerifier.verify(message.content)) {
                    is CampusAlertVerifier.Result.Verified -> {
                        // Learning the publisher's credential here means their name also resolves in
                        // class chats and the participant list, without a separate directory.
                        identityRegistry.learn(result.publisher)
                        DisplayedAlert(
                            id = result.alert.alertId,
                            title = result.alert.title,
                            body = result.alert.body,
                            publisherName = result.publisher.displayName,
                            publisherRole = result.publisher.role,
                            timestampMillis = result.alert.timestampEpochSeconds * 1000
                        )
                    }
                    else -> { rejected++; null }
                }
            }
            _rejectedAlertCount.value = rejected
            verified.sortedByDescending { it.timestampMillis }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Mesh density translated into a cellular-style 0..4 bar count. */
    val signalBars: StateFlow<Int> = chatViewModel.connectedPeers
        .map { peers ->
            when (peers.size) {
                0 -> 0
                1 -> 1
                in 2..4 -> 2
                in 5..9 -> 3
                else -> 4
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val nickname: StateFlow<String> get() = chatViewModel.nickname
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> get() = chatViewModel.privateChats
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> get() = chatViewModel.channelMessages

    /** Completed downloads, newest first, ready to open. */
    private val _downloads = MutableStateFlow<List<ReceivedDocument>>(emptyList())
    val downloads: StateFlow<List<ReceivedDocument>> = _downloads

    data class ReceivedDocument(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val path: String
    )

    init {
        fileTransfers.onProgress = { _transferProgress.value = fileTransfers.progressSnapshot() }

        // Only reached after the transfer's SHA-256 matched the offer, so nothing partial or
        // corrupted is ever written to disk or offered to a viewer.
        fileTransfers.onFileReceived = { transferId, fileName, mimeType, bytes ->
            val file = receivedFiles.save(fileName, bytes)
            _downloads.value = listOf(
                ReceivedDocument(transferId, fileName, mimeType, file.absolutePath)
            ) + _downloads.value
            _statusMessage.value = "$fileName downloaded"
        }
        refreshLocalIdentity()

        // Peers reappearing is the cue to restart transfers parked when they walked out of range.
        viewModelScope.launch {
            chatViewModel.connectedPeers.collect { peers ->
                if (peers.isNotEmpty()) fileTransfers.resumePausedTransfers()
            }
        }

        // Consume file-protocol envelopes from private conversations only.
        //
        // Channel traffic is deliberately not inspected: a file offer arriving over a broadcast
        // channel has no single counterparty, so there is no peer whose acknowledgements could be
        // trusted and no recipient the document was actually meant for.
        viewModelScope.launch {
            chatViewModel.privateChats.collect { chats ->
                val myPeerID = chatViewModel.myPeerID
                chats.values.flatten().forEach { message ->
                    val origin = message.senderPeerID ?: return@forEach
                    if (origin == myPeerID) return@forEach
                    if (!CampusFileMessage.looksLikeFileMessage(message.content)) return@forEach
                    // The chat list is re-emitted whole on every change; without this the engine
                    // would see each envelope again on every subsequent message.
                    if (!consumedFileEnvelopes.add(message.id)) return@forEach
                    fileTransfers.handleIncoming(origin, message.content)
                }
            }
        }
    }

    /**
     * Re-resolve this device's campus identity from the credential store.
     *
     * Public so a future provisioning flow can call it after installing a credential.
     */
    fun refreshLocalIdentity() {
        val signingKeyHex = alertSigner.localSigningPublicKeyHex()
        _localIdentity.value = identityRegistry.identityForSigningKey(
            signingKeyHex,
            claimedNickname = chatViewModel.nickname.value
        )
    }

    /**
     * Install a credential issued by campus IT for this device.
     *
     * @return true when the credential verified against an institutional anchor AND names this
     *         device's key. A credential for someone else's key is refused: accepting it would let a
     *         student paste a professor's credential and be rendered as that professor.
     */
    fun installLocalCredential(token: String): Boolean {
        val credential = CampusCredential.decode(token) ?: return false
        val localKey = alertSigner.localSigningPublicKeyHex() ?: return false
        if (!credential.signingPublicKeyHex.equals(localKey, ignoreCase = true)) {
            _statusMessage.value = "That credential belongs to a different device"
            return false
        }
        val result = identityRegistry.learn(credential)
        return if (result is CampusCredentialVerifier.Result.Verified) {
            refreshLocalIdentity()
            _statusMessage.value = "Campus identity verified"
            true
        } else {
            _statusMessage.value = "Credential could not be verified"
            false
        }
    }

    // MARK: - Identity display

    /**
     * Resolve the campus identity behind a message.
     *
     * Chain: message → peer id → Noise fingerprint → authenticated Ed25519 signing key → credential.
     * Every link is one the existing BitChat stack already authenticates, so a peer cannot present a
     * signing key they do not control.
     */
    fun identityForMessage(message: BitchatMessage): CampusIdentityRegistry.Identity =
        identityForPeer(message.senderPeerID, claimedNickname = message.sender)

    /**
     * Resolve the campus identity behind a peer ID, independent of any particular message.
     *
     * Same chain as [identityForMessage]; separated so surfaces that list people rather than
     * messages — the recipient picker, and later the contacts and nearby screens — resolve names
     * through exactly one code path.
     */
    fun identityForPeer(
        peerId: String?,
        claimedNickname: String? = null
    ): CampusIdentityRegistry.Identity {
        val fingerprint = peerId?.let { chatViewModel.getMeshPeerFingerprint(it) }
        val signingKeyHex = fingerprint
            ?.let { identityStore.getAuthenticatedSigningKey(it) }
            ?.let(CampusCanonical::hex)
        val nickname = claimedNickname
            ?: peerId?.let { chatViewModel.peerNicknames.value[it] }
        return identityRegistry.identityForSigningKey(signingKeyHex, nickname)
    }

    /** A person a document can currently be sent to. */
    data class Recipient(
        val peerId: String,
        val identity: CampusIdentityRegistry.Identity,
        val displayName: String
    )

    /**
     * People a document can be sent to right now.
     *
     * Restricted to peers with an established private session, because that is precisely the set
     * for which an addressed transfer can start immediately rather than parking.
     */
    fun availableRecipients(): List<Recipient> {
        val mesh = chatViewModel.meshServiceFacade
        return chatViewModel.connectedPeers.value
            .filter { mesh.hasEstablishedSession(it) }
            .map { peerId ->
                val identity = identityForPeer(peerId)
                Recipient(
                    peerId = peerId,
                    identity = identity,
                    displayName = when (identity) {
                        is CampusIdentityRegistry.Identity.Verified -> identity.displayName
                        is CampusIdentityRegistry.Identity.Unverified ->
                            identity.claimedNickname ?: "Unknown user"
                    }
                )
            }
            .sortedWith(
                compareByDescending<Recipient> {
                    it.identity is CampusIdentityRegistry.Identity.Verified
                }.thenBy { it.displayName.lowercase() }
            )
    }

    fun displayNameFor(message: BitchatMessage): String =
        when (val identity = identityForMessage(message)) {
            is CampusIdentityRegistry.Identity.Verified -> identity.displayName
            is CampusIdentityRegistry.Identity.Unverified ->
                identity.claimedNickname ?: "Unknown campus user"
        }

    /** Participants of a class, resolved to campus identities where possible. */
    fun participantsFor(channelTag: String): List<CampusIdentityRegistry.Identity> {
        val messages = chatViewModel.channelMessages.value[channelTag].orEmpty()
        return messages
            .distinctBy { it.senderPeerID ?: it.sender }
            .map { identityForMessage(it) }
            .sortedWith(
                compareByDescending<CampusIdentityRegistry.Identity> {
                    it is CampusIdentityRegistry.Identity.Verified
                }.thenBy {
                    (it as? CampusIdentityRegistry.Identity.Verified)?.displayName ?: ""
                }
            )
    }

    // MARK: - Enrolment

    /**
     * Enrol into every configured class and the alerts channel.
     *
     * This is the whole of the "passwordless" experience: joinChannel puts us in the channel and
     * setChannelPassword installs the deterministically derived key, so traffic is AES-256-GCM
     * encrypted to exactly the cohort that derives the same string.
     */
    fun enrolInConfiguredClasses() {
        viewModelScope.launch {
            CampusConfig.classes.forEach { definition ->
                enrol(definition.channelTag, CampusConfig.passwordFor(definition.code))
            }
            enrol(CampusConfig.ALERTS_CHANNEL, CampusConfig.passwordFor("CAMPUS-ALERTS"))
            chatViewModel.switchToChannel(null)
        }
    }

    private fun enrol(channelTag: String, password: String) {
        chatViewModel.joinChannel(channelTag, null)
        chatViewModel.setChannelPassword(channelTag, password)
    }

    // MARK: - Navigation

    fun selectTab(tab: CampusTab) { _selectedTab.value = tab }

    fun openClass(definition: CampusConfig.CampusClass) {
        _openClass.value = definition
        chatViewModel.switchToChannel(definition.channelTag)
    }

    fun closeClass() {
        _openClass.value = null
        chatViewModel.switchToChannel(null)
    }

    fun showAlertComposer() { _showAlertComposer.value = canPublishAlerts.value }
    fun hideAlertComposer() { _showAlertComposer.value = false }
    fun consumeStatusMessage() { _statusMessage.value = null }

    // MARK: - Sending

    /**
     * Send into the currently open class.
     *
     * A leading slash is escaped rather than executed. The academic build must not expose the IRC
     * surface, and must never let a pasted string invoke /transfer or /pass.
     */
    fun sendToOpenClass(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (isProtocolEnvelope(trimmed)) return
        val safe = if (trimmed.startsWith("/")) "​$trimmed" else trimmed
        chatViewModel.sendMessage(safe)
    }

    /**
     * Begin a chunked, resumable transfer of [bytes] to one peer.
     *
     * A document always has a named recipient. There is no broadcast form: sending coursework to
     * "the class" would hand a copy to every device in range, including any that merely relays.
     */
    fun sendFileToPeer(peerId: String, fileName: String, mimeType: String, bytes: ByteArray) {
        if (peerId.isBlank()) return
        val transferId = fileTransfers.offerFile(peerId, fileName, mimeType, bytes)
        if (transferId == null) {
            _statusMessage.value = "That file type or size is not supported"
            return
        }
        if (!chatViewModel.meshServiceFacade.hasEstablishedSession(peerId)) {
            val name = chatViewModel.peerNicknames.value[peerId] ?: "that person"
            _statusMessage.value = "Waiting for $name to come into range"
        }
    }

    /**
     * Publish an institutional alert.
     *
     * Signs the canonical payload with this device's identity key and attaches the institution-issued
     * credential proving the role. An unsigned alert is never emitted and never locally marked trusted.
     */
    fun publishAlert(title: String, body: String) {
        val signingKeyHex = alertSigner.localSigningPublicKeyHex()
        val credential = identityRegistry.credentialForSigningKey(signingKeyHex)
        if (credential == null || !credential.role.canPublishAlerts) {
            _statusMessage.value = "You are not authorised to publish alerts"
            return
        }
        when (val outcome = alertSigner.publish(title, body, credential)) {
            is CampusAlertSigner.Outcome.Signed -> {
                chatViewModel.switchToChannel(CampusConfig.ALERTS_CHANNEL)
                chatViewModel.sendMessage(outcome.envelope)
                chatViewModel.switchToChannel(_openClass.value?.channelTag)
                _showAlertComposer.value = false
                _statusMessage.value = "Alert published"
            }
            CampusAlertSigner.Outcome.NotAuthorised ->
                _statusMessage.value = "You are not authorised to publish alerts"
            CampusAlertSigner.Outcome.NoIdentityKey ->
                _statusMessage.value = "Device identity is not ready yet"
            is CampusAlertSigner.Outcome.Failed ->
                _statusMessage.value = outcome.reason
        }
    }

    /** Intent that opens a completed download through the app's FileProvider. */
    fun openIntentFor(document: ReceivedDocument): android.content.Intent =
        receivedFiles.openIntent(java.io.File(document.path), document.mimeType)

    fun handleBackPressed(): Boolean {
        if (_showAlertComposer.value) { _showAlertComposer.value = false; return true }
        if (_openClass.value != null) { closeClass(); return true }
        if (_selectedTab.value != CampusTab.CLASSES) {
            _selectedTab.value = CampusTab.CLASSES
            return true
        }
        return false
    }

    /** Protocol envelopes are machine traffic and must never render as chat text. */
    private fun isProtocolEnvelope(content: String): Boolean =
        CampusFileMessage.looksLikeFileMessage(content) || CampusAlert.looksLikeAlert(content)
}

enum class CampusTab { DIRECT_MESSAGES, CLASSES, ALERTS }
