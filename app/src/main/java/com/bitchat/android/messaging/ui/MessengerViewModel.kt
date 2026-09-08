package com.bitchat.android.messaging.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.messaging.contacts.Contact
import com.bitchat.android.messaging.contacts.ContactDiscoveryManager
import com.bitchat.android.messaging.contacts.ContactRepository
import com.bitchat.android.messaging.contacts.NearbyPeer
import com.bitchat.android.messaging.conversation.DeliveryState
import com.bitchat.android.messaging.conversation.DirectConversation
import com.bitchat.android.messaging.conversation.DirectMessageManager
import com.bitchat.android.messaging.groups.Group
import com.bitchat.android.messaging.groups.GroupKeyManager
import com.bitchat.android.messaging.groups.GroupManager
import com.bitchat.android.messaging.groups.GroupMessage
import com.bitchat.android.messaging.groups.GroupMessageManager
import com.bitchat.android.messaging.groups.GroupPermission
import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingIdentityManager
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceResolver
import com.bitchat.android.messaging.settings.PrivacyPolicy
import com.bitchat.android.messaging.settings.PrivacySettings
import com.bitchat.android.messaging.settings.PrivacySettingsStore
import com.bitchat.android.messaging.transport.MeshOutboundTransport
import com.bitchat.android.messaging.transport.MeshPeerIdentitySource
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single point the messaging interface talks to.
 *
 * Composes the application layer and holds the only references to the transport adapters, so no
 * composable ever reaches a mesh, Noise or Nostr class. Screens see contacts, conversations and
 * groups; they have no vocabulary for peers, packets or routes, which is what keeps a UI change
 * from being able to alter addressing or weaken a check.
 */
class MessengerViewModel(
    application: Application,
    private val chatViewModel: ChatViewModel
) : ViewModel() {

    private val appContext = application.applicationContext

    // MARK: - Transport adapters (the only classes here that touch BitChat)

    private val peerSource = MeshPeerIdentitySource(
        chatViewModel = chatViewModel,
        identityStore = SecureIdentityStateManager(appContext)
    )

    private val presenceResolver = PresenceResolver(peerSource)

    private val outboundTransport = MeshOutboundTransport(
        context = appContext,
        source = peerSource,
        meshProvider = { chatViewModel.meshServiceFacade },
        nicknameProvider = { peerId -> chatViewModel.peerNicknames.value[peerId] ?: peerId }
    )

    // MARK: - Application layer

    val identityManager = MessagingIdentityManager(appContext)
    private val identities = IdentityRepository.getInstance(appContext)
    val contacts = ContactRepository(identities, viewModelScope)

    private val discovery = ContactDiscoveryManager(
        source = peerSource,
        identities = identities,
        contacts = contacts,
        presence = presenceResolver,
        // Without this a peer never messaged is never proven, and a peer never proven can never
        // be messaged — no first conversation would ever be possible.
        proofRequester = peerSource
    )

    val privacyStore = PrivacySettingsStore(appContext)

    /**
     * The optional institutional layer.
     *
     * Composed here rather than depended on from within the messaging layer: the messenger declares
     * the seam and this is where an implementation is plugged in. Removing this one line would
     * leave a fully working messenger in which nobody is verified, which is exactly the
     * relationship an optional layer should have to the thing it sits on.
     */
    val campusIdentities = com.bitchat.android.campus.CampusIdentityRegistry()

    private val attestations: com.bitchat.android.messaging.identity.AttestationProvider =
        com.bitchat.android.campus.CampusAttestationProvider(campusIdentities)

    /** The institutional standing of an identity, or null when none has been claimed. */
    fun attestationFor(key: MessagingPublicKey) = attestations.attestationFor(key)

    /**
     * This device's own institutional standing, if it has been given a credential.
     *
     * Read on demand rather than cached, so installing a credential takes effect immediately and an
     * expiring one stops reading as verified without anything needing to invalidate a cache.
     */
    fun localAttestation(): com.bitchat.android.messaging.identity.IdentityAttestation? =
        _localKey.value?.let(attestations::attestationFor)

    private val alertSigner = com.bitchat.android.campus.CampusAlertSigner(appContext)

    // MARK: - File transfer

    private val receivedFiles = com.bitchat.android.campus.CampusReceivedFileStore(appContext)

    /**
     * Point-to-point file transfer.
     *
     * Every envelope is addressed to one recipient and sent down the private-message path, so a
     * document reaches the person it was meant for and nobody else. Chunks are held rather than
     * queued when the recipient is unreachable: the router's per-peer outbox is bounded and evicts
     * oldest-first, so a multi-chunk document would push the user's real messages out of it.
     */
    val fileTransfers = com.bitchat.android.campus.CampusFileTransferService(
        transport = { recipientKeyHex, envelope ->
            val key = MessagingPublicKey.parse(recipientKeyHex)
            if (key == null) {
                false
            } else {
                transportReady(key) && directMessages?.let { dm ->
                    dm.send(key, envelope) is DirectMessageManager.SendResult.Accepted
                } == true
            }
        }
    )

    private fun transportReady(key: MessagingPublicKey): Boolean =
        outboundTransport.canReachNow(key)

    /** Message IDs already fed to the transfer engine, so a re-emitted chat list is not replayed. */
    private val consumedFileEnvelopes: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Messages already offered to the conversation layer.
     *
     * The stored chat list is re-emitted in full whenever anything about it changes, so without
     * this every message is re-examined on every change — work that grows with the square of the
     * conversation and, worse, gives any side effect of examining a message the chance to happen
     * repeatedly.
     */
    private val processedIncoming: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private val _downloads = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val downloads: StateFlow<List<ReceivedFile>> = _downloads

    data class ReceivedFile(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val path: String
    )

    val transferProgress = MutableStateFlow<List<
        com.bitchat.android.campus.CampusFileTransferService.Progress
        >>(emptyList())

    /**
     * Send a file or image to one person.
     *
     * Uses the transport's own private file path, which fragments and reassembles a payload of any
     * size. The application envelope cannot carry this: it rides the private *text* path, whose
     * body is capped at 255 bytes, so a document pushed through it was refused chunk by chunk and
     * the transfer simply stalled.
     *
     * The recipient is named explicitly, so an attachment goes to the person whose conversation is
     * open rather than to whichever chat the rest of the app last selected.
     */
    fun sendAttachment(
        recipient: MessagingPublicKey,
        filePath: String,
        fileName: String,
        kind: com.bitchat.android.messaging.conversation.AttachmentKind
    ) {
        if (!privacyStore.settings.value.allowFileTransfers) {
            _status.value = "File transfers are turned off in Privacy settings"
            return
        }
        if (contacts.isBlocked(recipient)) {
            _status.value = "You have blocked this person"
            return
        }
        val peerId = peerSource.peerIdFor(recipient)
        if (peerId == null) {
            _status.value = "Waiting for them to come into range"
            return
        }

        when (kind) {
            com.bitchat.android.messaging.conversation.AttachmentKind.IMAGE ->
                chatViewModel.sendImageNote(peerId, null, filePath)
            com.bitchat.android.messaging.conversation.AttachmentKind.VOICE ->
                chatViewModel.sendVoiceNote(peerId, null, filePath)
            com.bitchat.android.messaging.conversation.AttachmentKind.FILE ->
                chatViewModel.sendFileNote(peerId, null, filePath)
        }

        // Shown immediately as our own message. The transport reports progress separately; what
        // matters here is that the user sees what they just sent rather than nothing.
        directMessages?.recordAttachment(
            counterparty = recipient,
            messageId = java.util.UUID.randomUUID().toString(),
            timestampEpochMillis = System.currentTimeMillis(),
            attachment = com.bitchat.android.messaging.conversation.Attachment(
                path = filePath,
                kind = kind,
                fileName = fileName
            ),
            isOutgoing = true
        )
    }

    /**
     * An intent that opens a received attachment in whatever app handles it.
     *
     * Shared through the app's FileProvider rather than as a raw path, so no filesystem location
     * leaves the app and the receiving app gets a permission scoped to this one file.
     */
    fun openAttachment(path: String, fileName: String): android.content.Intent =
        receivedFiles.openIntent(
            java.io.File(path),
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                fileName.substringAfterLast('.', "").lowercase()
            ) ?: "*/*"
        )

    fun openFileIntent(file: ReceivedFile): android.content.Intent =
        receivedFiles.openIntent(java.io.File(file.path), file.mimeType)

    /** One institutional alert that passed the full two-signature chain. */
    data class VerifiedAlert(
        val id: String,
        val title: String,
        val body: String,
        val publisherName: String,
        val publisherSubtitle: String,
        val timestampMillis: Long
    )

    private val _alerts = MutableStateFlow<List<VerifiedAlert>>(emptyList())

    /**
     * Institutional alerts, verified only.
     *
     * An alert that fails any link of its trust chain is counted and dropped, never downgraded to
     * an ordinary message. An unverified string reading "campus evacuation required" is precisely
     * the payload this whole mechanism exists to suppress, and showing it with a caveat beside it
     * would defeat the purpose.
     */
    val alerts: StateFlow<List<VerifiedAlert>> = _alerts

    private val _rejectedAlertCount = MutableStateFlow(0)
    val rejectedAlertCount: StateFlow<Int> = _rejectedAlertCount

    /** True when this device holds a credential whose role permits publishing alerts. */
    fun canPublishAlerts(): Boolean =
        (localAttestation() as? com.bitchat.android.campus.CampusAttestation)
            ?.role?.canPublishAlerts == true

    /**
     * Publish an institutional alert.
     *
     * Authority comes from the institution-signed credential naming this device's key, re-checked
     * at signing time. A device cannot make itself a publisher by reaching this method.
     */
    fun publishAlert(title: String, body: String): Boolean {
        val credential = _localKey.value
            ?.let { campusIdentities.credentialForSigningKey(it.hex) }
        if (credential == null || !credential.role.canPublishAlerts) {
            _status.value = "You are not authorised to publish alerts"
            return false
        }
        return when (val outcome = alertSigner.publish(title, body, credential)) {
            is com.bitchat.android.campus.CampusAlertSigner.Outcome.Signed -> {
                broadcastToChannel(
                    com.bitchat.android.campus.CampusConfig.ALERTS_CHANNEL,
                    outcome.envelope
                )
                _status.value = "Alert published"
                true
            }
            com.bitchat.android.campus.CampusAlertSigner.Outcome.NotAuthorised -> {
                _status.value = "You are not authorised to publish alerts"
                false
            }
            com.bitchat.android.campus.CampusAlertSigner.Outcome.NoIdentityKey -> {
                _status.value = "Device identity is not ready yet"
                false
            }
            is com.bitchat.android.campus.CampusAlertSigner.Outcome.Failed -> {
                _status.value = outcome.reason
                false
            }
        }
    }

    private fun broadcastToChannel(channelTag: String, content: String) {
        chatViewModel.joinChannel(channelTag, null)
        chatViewModel.switchToChannel(channelTag)
        chatViewModel.sendMessage(content)
    }

    /** Re-verify every alert seen in the alerts channel. */
    private fun refreshAlerts(rawMessages: List<String>) {
        var rejected = 0
        val verified = rawMessages.mapNotNull { content ->
            if (!com.bitchat.android.campus.CampusAlert.looksLikeAlert(content)) {
                return@mapNotNull null
            }
            when (
                val result = com.bitchat.android.campus.CampusAlertVerifier.verify(content)
            ) {
                is com.bitchat.android.campus.CampusAlertVerifier.Result.Verified -> {
                    // Learning the publisher's credential here means their name also resolves on
                    // contact profiles, without a separate directory.
                    campusIdentities.learn(result.publisher)
                    VerifiedAlert(
                        id = result.alert.alertId,
                        title = result.alert.title,
                        body = result.alert.body,
                        publisherName = result.publisher.displayName,
                        publisherSubtitle = listOf(
                            result.publisher.role.displayLabel,
                            result.publisher.department
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        timestampMillis = result.alert.timestampEpochSeconds * 1000
                    )
                }
                else -> { rejected++; null }
            }
        }
        _rejectedAlertCount.value = rejected
        _alerts.value = verified.distinctBy { it.id }.sortedByDescending { it.timestampMillis }
    }

    /**
     * Install a credential issued for this device.
     *
     * Refuses a credential naming somebody else's key: accepting one would let a student present a
     * professor's credential and be rendered as that professor.
     */
    fun installCampusCredential(token: String): Boolean {
        val credential = com.bitchat.android.campus.CampusCredential.decode(token) ?: run {
            _status.value = "That code could not be read"
            return false
        }
        val mine = _localKey.value
        if (mine == null || !credential.signingPublicKeyHex.equals(mine.hex, ignoreCase = true)) {
            _status.value = "That code belongs to a different device"
            return false
        }
        val result = campusIdentities.learn(credential)
        return if (result is com.bitchat.android.campus.CampusCredentialVerifier.Result.Verified) {
            _status.value = "Campus identity verified"
            true
        } else {
            _status.value = "That code could not be verified"
            false
        }
    }

    private val privacyPolicy = PrivacyPolicy(
        settingsProvider = { privacyStore.settings.value },
        isKnownContact = { keyHex ->
            MessagingPublicKey.parse(keyHex)?.let { contacts.find(it)?.isSaved } == true
        }
    )

    val groups = GroupManager()
    private val groupKeys = GroupKeyManager()
    private val groupStore =
        com.bitchat.android.messaging.groups.GroupRepository(appContext)

    /**
     * Write a group and its current key back to disk.
     *
     * Called after every change rather than on a timer: a group whose membership was edited and
     * then lost to a process death would silently restore a removed member's access, and a key
     * generated but not stored would make everything sent under it permanently unreadable.
     */
    private fun persistGroup(groupId: String) {
        val group = groups.group(groupId) ?: return
        viewModelScope.launch {
            groupStore.save(group)
            groupKeys.currentKey(groupId)?.let { groupStore.saveKey(it, isCurrent = true) }
        }
    }

    /**
     * Null until the device's signing key exists, which is a brief window on first launch. Screens
     * render a "setting up" state rather than inventing an identity to stand in for it.
     */
    private val _localKey = MutableStateFlow(identityManager.localPublicKey())
    val localKey: StateFlow<MessagingPublicKey?> = _localKey

    private val messageStore =
        com.bitchat.android.messaging.conversation.DirectMessageRepository(appContext)

    private var directMessages: DirectMessageManager? = null
    private var groupMessages: GroupMessageManager? = null

    private val _conversations = MutableStateFlow<List<DirectConversation>>(emptyList())
    val conversations: StateFlow<List<DirectConversation>> = _conversations

    private val _groupMessagesByGroup = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())
    val groupMessagesByGroup: StateFlow<Map<String, List<GroupMessage>>> = _groupMessagesByGroup

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _needsSetup = MutableStateFlow(!identityManager.hasCompletedSetup())

    /** True until the user has chosen a display name for themselves. */
    val needsSetup: StateFlow<Boolean> = _needsSetup

    /** Record the profile the user entered at first run. */
    fun completeSetup(displayName: String, phoneNumber: String?) {
        identityManager.completeSetup(displayName, phoneNumber)
        chatViewModel.setNickname(displayName)
        _needsSetup.value = false
    }

    val nearby: StateFlow<List<NearbyPeer>> get() = discovery.nearby
    val savedContacts: StateFlow<List<Contact>> get() = contacts.saved
    val allKnown: StateFlow<List<Contact>> get() = contacts.all
    val privacySettings: StateFlow<PrivacySettings> get() = privacyStore.settings
    val localIdentity: StateFlow<MessagingIdentity?> get() = identityManager.identity

    /** Groups this user belongs to. */
    val myGroups: StateFlow<List<Group>> = combine(groups.groups, localKey) { all, key ->
        if (key == null) emptyList() else all.filter { it.isMember(key) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many people are reachable right now, for the home screen's one line of status. */
    val reachableCount: StateFlow<Int> = contacts.all
        .map { list -> list.count { it.canReachNow } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        android.util.Log.w(
            "MsgRx",
            "startup: myKeyPrefix=${_localKey.value?.hex?.take(16) ?: "NONE"}"
        )
        viewModelScope.launch { identities.load() }
        restoreGroups()
        observeNameChanges()
        observeQueuedDelivery()
        discovery.start(viewModelScope)
        bindLocalIdentity()
        observeIncoming()
        bindFileTransfers()
    }

    /**
     * Reload groups and their keys from disk.
     *
     * Keys are installed for every generation, not just the newest, so messages sent before a
     * rotation stay readable rather than becoming permanently opaque after a restart.
     */
    private fun restoreGroups() {
        viewModelScope.launch {
            groups.restore(groupStore.loadGroups())
            groupStore.loadKeys().forEach { (key, _) -> groupKeys.install(key) }
        }
    }

    /**
     * Re-resolve identities when a peer's announced name arrives.
     *
     * Discovery is otherwise driven only by the connected set changing. A peer is typically
     * discovered before its nickname is known, so the first pass stored an empty name and — with
     * the peer list then unchanged — never ran again, leaving that person permanently displayed as
     * a hex key even though the name was sitting in the peer table.
     */
    private fun observeNameChanges() {
        viewModelScope.launch {
            chatViewModel.peerNicknames.collect { discovery.refresh() }
        }
    }

    /**
     * Advance messages that were held because the recipient was unreachable.
     *
     * The router queues a message when no session exists, opens one, then flushes its outbox — but
     * it reports none of that back, so nothing here learned the message had actually gone. Without
     * this a delivered message sat reading "Waiting to deliver" indefinitely, which is a worse
     * failure than an honest error because the user has no reason to doubt it.
     *
     * Advanced only as far as [DeliveryState.SENT]: the message has demonstrably left this device,
     * but nothing here has heard the recipient acknowledge it, and claiming delivery on that basis
     * would be inventing the part that matters.
     */
    private fun observeQueuedDelivery() {
        viewModelScope.launch {
            chatViewModel.connectedPeers.collect {
                val dm = directMessages ?: return@collect
                dm.conversations.value.forEach { conversation ->
                    if (!outboundTransport.canReachNow(conversation.counterparty)) return@forEach
                    conversation.messages
                        .filter { it.isOutgoing && it.deliveryState == DeliveryState.PENDING_OFFLINE }
                        .forEach { dm.updateDeliveryState(it.messageId, DeliveryState.SENT) }
                }
            }
        }
    }

    private fun bindFileTransfers() {
        fileTransfers.onProgress = { transferProgress.value = fileTransfers.progressSnapshot() }

        // Only reached after the transfer's SHA-256 matched the offer, so nothing partial or
        // corrupted is ever written to disk or offered to a viewer.
        fileTransfers.onFileReceived = { transferId, fileName, mimeType, bytes ->
            val file = receivedFiles.save(fileName, bytes)
            _downloads.value = listOf(
                ReceivedFile(transferId, fileName, mimeType, file.absolutePath)
            ) + _downloads.value
            _status.value = "$fileName received"
        }

        // Peers reappearing is the cue to restart transfers parked when they walked out of range.
        viewModelScope.launch {
            chatViewModel.connectedPeers.collect { peers ->
                if (peers.isNotEmpty()) fileTransfers.resumePausedTransfers()
            }
        }
    }

    /**
     * Attach the message managers once the device has a key.
     *
     * Deferred rather than assumed: on a first launch the encryption service may not have generated
     * the signing key yet, and constructing a manager around a placeholder identity would make
     * every message it handled attributable to the wrong sender.
     */
    private fun bindLocalIdentity() {
        viewModelScope.launch {
            chatViewModel.connectedPeers.collect {
                if (_localKey.value == null) {
                    val key = identityManager.refresh()?.publicKey ?: return@collect
                    _localKey.value = key
                    attachManagers(key)
                }
            }
        }
        _localKey.value?.let(::attachManagers)
    }

    private fun attachManagers(key: MessagingPublicKey) {
        if (directMessages != null) return
        val dm = DirectMessageManager(
            localKey = key,
            transport = outboundTransport,
            contacts = contacts,
            persistence = messageStore
        )
        val gm = GroupMessageManager(
            localKey = key,
            groups = groups,
            keys = groupKeys,
            transport = { channelTag, envelope ->
                // Group traffic rides the existing channel broadcast; only members hold the key, so
                // the other devices in range relay ciphertext they cannot read.
                chatViewModel.joinChannel(channelTag, null)
                chatViewModel.switchToChannel(channelTag)
                chatViewModel.sendMessage(envelope)
                com.bitchat.android.messaging.conversation.SendOutcome.SENT_LOCAL
            }
        )
        directMessages = dm
        groupMessages = gm

        // History first, so the conversation list is populated before any live traffic arrives and
        // the user never sees an empty app that fills in a moment later.
        viewModelScope.launch {
            dm.restore(messageStore.loadMessages(), messageStore.loadReadConversations())
        }

        viewModelScope.launch { dm.conversations.collect { _conversations.value = it } }
        viewModelScope.launch { gm.messages.collect { _groupMessagesByGroup.value = it } }
    }

    /**
     * Feed arriving traffic to the application layer.
     *
     * Everything is offered with the sender the transport actually proved; nothing is trusted from
     * the message body itself.
     */
    private fun observeIncoming() {
        viewModelScope.launch {
            // Re-run whenever the stored conversations change *or* the set of connected peers does.
            //
            // A message is only attributable once its sender's key has been proven, and that proof
            // comes from the peer table — which is empty at startup, before anyone has announced.
            // Persisted messages are replayed at exactly that moment, so they were refused as
            // unattributable and, since the stored conversations did not change again, never
            // reconsidered. They stayed invisible while sitting correctly in the database.
            //
            // Pairing the two flows means the arrival of the sender is itself a trigger to look
            // again.
            combine(
                chatViewModel.privateChats,
                chatViewModel.connectedPeers
            ) { chats, _ -> chats }.collect { chats ->
                android.util.Log.w(
                    "MsgRx",
                    "privateChats: conversations=${chats.size} " +
                        "messages=${chats.values.sumOf { it.size }} " +
                        "managerAttached=${directMessages != null}"
                )
                val dm = directMessages ?: return@collect
                val myPeerId = chatViewModel.myPeerID
                chats.forEach { (_, messages) ->
                    messages.forEach { message ->
                        val peerId = message.senderPeerID ?: return@forEach
                        if (peerId == myPeerId) return@forEach
                        val sender = peerSource.authenticatedKeyFor(peerId)
                        // The privacy setting is applied before the message is filed, so refusing
                        // unknown senders means no conversation is created at all.
                        if (sender != null && !privacyPolicy.mayAcceptMessageFrom(sender.hex)) {
                            return@forEach
                        }

                        // File-protocol envelopes are machine traffic and must never be rendered
                        // as chat text. Fed to the transfer engine with the peer the transport
                        // proved, so only the counterparty can drive a transfer.
                        if (com.bitchat.android.campus.CampusFileMessage
                                .looksLikeFileMessage(message.content)
                        ) {
                            if (sender == null) return@forEach
                            if (!privacyPolicy.mayAcceptFileFrom(sender.hex)) return@forEach
                            if (consumedFileEnvelopes.add(message.id)) {
                                fileTransfers.handleIncoming(sender.hex, message.content)
                            }
                            return@forEach
                        }

                        // Examined once. A message that has already been through admission cannot
                        // change its mind about the outcome, and re-running it only risks repeating
                        // whatever the outcome caused.
                        if (!processedIncoming.add(message.id)) return@forEach

                        // Attachments arrive on the transport's own file path, already reassembled
                        // and written to disk, so they are never envelopes and never go through
                        // admission. The private session that carried them is the same one that
                        // carries text, so recipiency is established by exactly the same means.
                        val attachmentKind = when (message.type) {
                            com.bitchat.android.model.BitchatMessageType.Image ->
                                com.bitchat.android.messaging.conversation.AttachmentKind.IMAGE
                            com.bitchat.android.model.BitchatMessageType.Audio ->
                                com.bitchat.android.messaging.conversation.AttachmentKind.VOICE
                            com.bitchat.android.model.BitchatMessageType.File ->
                                com.bitchat.android.messaging.conversation.AttachmentKind.FILE
                            else -> null
                        }
                        if (attachmentKind != null) {
                            if (sender == null) return@forEach
                            dm.recordAttachment(
                                counterparty = sender,
                                messageId = message.id,
                                timestampEpochMillis = message.timestamp.time,
                                attachment = com.bitchat.android.messaging.conversation.Attachment(
                                    path = message.content,
                                    kind = attachmentKind,
                                    fileName = java.io.File(message.content).name
                                ),
                                isOutgoing = false
                            )
                            return@forEach
                        }

                        // The transport preserves the sender's own message id and packet
                        // timestamp, so they are taken from here rather than re-sent inside the
                        // envelope where they would cost more than the message itself.
                        val admission = dm.receive(
                            content = message.content,
                            messageId = message.id,
                            timestampEpochMillis = message.timestamp.time,
                            authenticatedSender = sender
                        )
                        android.util.Log.w(
                            "MsgRx",
                            "rx: envelope=" +
                                com.bitchat.android.messaging.protocol.MessagingEnvelope
                                    .looksLikeEnvelope(message.content) +
                                " tag=" + message.content.drop(5).take(16) +
                                " myPrefix=" + (_localKey.value?.hex?.take(16) ?: "none") +
                                " senderPrefix=" + (sender?.hex?.take(16) ?: "NOT-PROVEN") +
                                " result=" + admission::class.simpleName
                        )
                        if (admission is com.bitchat.android.messaging.protocol
                                .EnvelopeAdmission.Result.Admitted &&
                            admission.envelope.kind ==
                            com.bitchat.android.messaging.protocol.MessageKind.SYSTEM
                        ) {
                            handleControlPayload(
                                admission.envelope.senderPublicKey,
                                admission.envelope.payload
                            )
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            chatViewModel.channelMessages.collect { channels ->
                // Alerts carry their own signature chain and are verified independently of who
                // relayed them, so unlike group traffic they need no authenticated peer.
                refreshAlerts(
                    channels[com.bitchat.android.campus.CampusConfig.ALERTS_CHANNEL]
                        .orEmpty()
                        .map { it.content }
                )

                val gm = groupMessages ?: return@collect
                channels.forEach { (_, messages) ->
                    messages.forEach { message ->
                        val peerId = message.senderPeerID ?: return@forEach
                        if (peerId == chatViewModel.myPeerID) return@forEach
                        gm.receive(
                            content = message.content,
                            timestampEpochMillis = message.timestamp.time,
                            authenticatedSender = peerSource.authenticatedKeyFor(peerId)
                        )
                    }
                }
            }
        }
    }

    // MARK: - Actions

    fun sendDirect(recipient: MessagingPublicKey, text: String) {
        val dm = directMessages ?: run { _status.value = "Still setting up"; return }
        // Introduce ourselves before the first message, so the other side has a name for this
        // device rather than a hex key.
        shareProfileWith(recipient)
        android.util.Log.w(
            "MsgRx",
            "send: toPrefix=${recipient.hex.take(16)} myPrefix=${_localKey.value?.hex?.take(16)}"
        )
        when (dm.send(recipient, text)) {
            is DirectMessageManager.SendResult.BlockedRecipient ->
                _status.value = "You have blocked this person"
            is DirectMessageManager.SendResult.CannotMessageSelf ->
                _status.value = "You cannot message yourself"
            is DirectMessageManager.SendResult.TooLong ->
                _status.value = "That message is too long to send over the mesh"
            else -> Unit
        }
    }

    fun conversationWith(key: MessagingPublicKey): DirectConversation? =
        directMessages?.conversationWith(key)

    fun markRead(key: MessagingPublicKey) {
        directMessages?.markRead(key)
    }

    fun sendToGroup(groupId: String, text: String) {
        val gm = groupMessages ?: return
        when (gm.send(groupId, text)) {
            GroupMessageManager.SendResult.NotPermitted ->
                _status.value = "Only admins can post in this group"
            GroupMessageManager.SendResult.NotAMember ->
                _status.value = "You are not a member of this group"
            GroupMessageManager.SendResult.NoGroupKey ->
                _status.value = "Waiting for the group key"
            else -> Unit
        }
    }

    /**
     * Create a group and give its members the key.
     *
     * Key distribution is part of creation rather than a later step: a group whose members cannot
     * decrypt it is not yet a usable group.
     */
    fun createGroup(
        name: String,
        description: String,
        members: Set<MessagingPublicKey>,
        announcementOnly: Boolean = false
    ) {
        val me = _localKey.value ?: return
        val outcome = groups.create(
            creator = me,
            name = name,
            description = description,
            initialMembers = members,
            defaultMemberPermissions = if (announcementOnly) {
                GroupPermission.READ_ONLY
            } else {
                GroupPermission.ORDINARY_MEMBER
            }
        )
        if (outcome !is GroupManager.Outcome.Success) {
            _status.value = "Enter a group name"
            return
        }
        groupKeys.createInitialKey(outcome.group.id)
        persistGroup(outcome.group.id)
        distributeGroupKey(outcome.group.id, members)
        _status.value = "Group created"
    }

    fun addToGroup(groupId: String, member: MessagingPublicKey) {
        val me = _localKey.value ?: return
        when (groups.addMember(me, groupId, member)) {
            is GroupManager.Outcome.Success -> {
                persistGroup(groupId)
                distributeGroupKey(groupId, setOf(member))
            }
            GroupManager.Outcome.NotPermitted -> _status.value = "You cannot add members here"
            GroupManager.Outcome.AlreadyAMember -> _status.value = "Already in the group"
            else -> _status.value = "Could not add that person"
        }
    }

    /**
     * Remove someone, then rotate and redistribute.
     *
     * Rotation is not optional. Dropping the member stops them being authorized, but they still
     * hold the current key, so without a new one they would keep reading everything sent afterwards.
     */
    fun removeFromGroup(groupId: String, member: MessagingPublicKey) {
        val me = _localKey.value ?: return
        when (groups.removeMember(me, groupId, member)) {
            is GroupManager.Outcome.Success -> {
                groupKeys.rotate(groupId)
                persistGroup(groupId)
                val remaining = groups.group(groupId)?.memberKeys.orEmpty() - me
                distributeGroupKey(groupId, remaining)
                _status.value = "Removed from group"
            }
            GroupManager.Outcome.CannotActOnOwner -> _status.value = "The owner cannot be removed"
            GroupManager.Outcome.NotPermitted -> _status.value = "You cannot remove members here"
            else -> _status.value = "Could not remove that person"
        }
    }

    fun promote(groupId: String, member: MessagingPublicKey) {
        val me = _localKey.value ?: return
        if (groups.promoteToAdmin(me, groupId, member) is GroupManager.Outcome.Success) {
            persistGroup(groupId)
        } else {
            _status.value = "You cannot change admins here"
        }
    }

    fun setMemberMuted(groupId: String, member: MessagingPublicKey, muted: Boolean) {
        val me = _localKey.value ?: return
        val permissions = if (muted) GroupPermission.READ_ONLY else null
        if (groups.setMemberPermissions(me, groupId, member, permissions)
            is GroupManager.Outcome.Success
        ) {
            persistGroup(groupId)
        } else {
            _status.value = "You cannot change permissions here"
        }
    }

    private fun distributeGroupKey(groupId: String, recipients: Set<MessagingPublicKey>) {
        val dm = directMessages ?: return
        groupKeys.distributionFor(groupId, recipients).forEach { (recipient, encoded) ->
            // Control traffic, not a message: the key must never render as chat text.
            dm.sendControl(recipient, encoded)
        }
    }

    /**
     * Accept a group key handed over by an administrator.
     *
     * Only an admin or owner of the group *as this device sees it* may supply one. Without that
     * check anyone could send an unsolicited "rotation" for a group they are not in: the recipient
     * would install a key the attacker chose, and from then on read the attacker's traffic as
     * genuine group messages while being unable to read the real group's. Membership is not enough
     * either — an ordinary member has no authority to re-key a group.
     *
     * @return true when a key was installed.
     */
    /**
     * Route control traffic to whatever understands it.
     *
     * Control messages share one channel, so this decides which of them a payload is. Anything
     * unrecognised is discarded rather than guessed at.
     */
    private fun handleControlPayload(sender: MessagingPublicKey, payload: String) {
        if (com.bitchat.android.messaging.protocol.ProfileCard.looksLikeProfileCard(payload)) {
            handleProfileCard(sender, payload)
            return
        }
        handleKeyDistribution(sender, payload)
    }

    /**
     * Learn what someone says about themselves.
     *
     * The name replaces a broadcast nickname because it was told to this device specifically rather
     * than announced to the whole room. The phone number is stored only so the same person stays
     * recognisable if their device is ever reinstalled and takes on a new key.
     */
    private fun handleProfileCard(sender: MessagingPublicKey, payload: String) {
        val card = com.bitchat.android.messaging.protocol.ProfileCard.decode(payload) ?: return
        viewModelScope.launch {
            identities.remember(
                MessagingIdentity.of(
                    publicKey = sender,
                    displayName = card.displayName,
                    phoneNumber = card.phoneNumber
                )
            )
        }
    }

    /** People this device has already introduced itself to, so a card is sent once per session. */
    private val profileSharedWith: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /**
     * Introduce this device to someone it is about to talk to.
     *
     * Sent once per conversation rather than with every message, and only carrying the phone number
     * when the user has allowed it — the name alone is already an improvement on a broadcast
     * nickname, and the number is a separate decision.
     */
    private fun shareProfileWith(recipient: MessagingPublicKey) {
        val dm = directMessages ?: return
        if (!profileSharedWith.add(recipient.hex)) return
        val me = identityManager.identity.value ?: return
        val card = com.bitchat.android.messaging.protocol.ProfileCard(
            displayName = me.displayName,
            phoneNumber = me.phoneNumber.takeIf { privacyPolicy.mayShareOwnPhoneNumber() }
        )
        dm.sendControl(recipient, card.encode())
    }

    private fun handleKeyDistribution(
        sender: MessagingPublicKey,
        payload: String
    ): Boolean {
        if (!com.bitchat.android.messaging.groups.GroupKeyManager
                .looksLikeKeyDistribution(payload)
        ) {
            return false
        }
        val key = com.bitchat.android.messaging.groups.GroupKeyManager.decodeKey(payload)
            ?: return false
        val group = groups.group(key.groupId) ?: return false

        val senderStanding = group.memberFor(sender)?.role
        if (senderStanding?.isAdministrative != true) return false

        // Ignored silently when a key for that generation is already held, so a replayed
        // distribution cannot roll the group back to a superseded key.
        val installed = groupKeys.install(key)
        if (installed) {
            // Persisted immediately: a key accepted but not stored would leave every message sent
            // under it unreadable after the next restart.
            viewModelScope.launch { groupStore.saveKey(key, isCurrent = true) }
        }
        return installed
    }

    fun saveContact(key: MessagingPublicKey) {
        viewModelScope.launch {
            contacts.save(key)
            _status.value = "Added to contacts"
        }
    }

    fun setBlocked(key: MessagingPublicKey, blocked: Boolean) {
        viewModelScope.launch { contacts.setBlocked(key, blocked) }
    }

    fun renameContact(key: MessagingPublicKey, name: String) {
        viewModelScope.launch { contacts.rename(key, name) }
    }

    fun setDisplayName(name: String) {
        identityManager.setDisplayName(name)
        chatViewModel.setNickname(name)
    }

    fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) {
        privacyStore.update(transform)
    }

    fun consumeStatus() {
        _status.value = null
    }

}
