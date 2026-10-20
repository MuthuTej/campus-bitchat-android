package com.bitchat.android.campus

import java.io.File

/**
 * Durable campus profile for this device: verified email, the local credential, and credentials
 * learned from neighbours.
 *
 * Credentials are public by design. The email is the user's own campus address and stays on disk
 * only so a restart does not ask them to verify again.
 */
class CampusDeviceStore(private val directory: File) {

    private val profileFile = File(directory, "profile.txt")
    private val credentialFile = File(directory, "credentials.txt")

    var email: String? = null
        private set
    var emailVerified: Boolean = false
        private set
    var localCredentialToken: String? = null
        private set

    init {
        if (!directory.exists()) directory.mkdirs()
        loadProfile()
    }

    @Synchronized
    fun setEmail(email: String, verified: Boolean) {
        this.email = CampusEmailAddress.normalize(email)
        this.emailVerified = verified && this.email != null
        writeProfile()
    }

    @Synchronized
    fun setLocalToken(token: String?) {
        localCredentialToken = token?.takeIf { CampusCredential.decode(it) != null }
        writeProfile()
    }

    @Synchronized
    fun loadCredentials(): List<CampusCredential> {
        if (!credentialFile.exists()) return emptyList()
        return credentialFile.readLines().mapNotNull { line ->
            CampusCredential.decode(line.trim())
        }
    }

    /** Replace the stored credential for the same signing key. */
    @Synchronized
    fun saveCredential(credential: CampusCredential) {
        val current = loadCredentials()
            .filterNot {
                it.signingPublicKeyHex.equals(credential.signingPublicKeyHex, ignoreCase = true)
            }
        val updated = current + credential
        val body = updated.joinToString("\n") { it.encode() }
        val temporary = File(directory, "credentials.txt.tmp")
        temporary.writeText(body)
        if (credentialFile.exists() && !credentialFile.delete()) {
            credentialFile.writeText(body)
            temporary.delete()
            return
        }
        if (!temporary.renameTo(credentialFile)) {
            credentialFile.writeText(body)
            temporary.delete()
        }
    }

    private fun loadProfile() {
        if (!profileFile.exists()) return
        val values = profileFile.readLines().mapNotNull { line ->
            val split = line.split('=', limit = 2)
            if (split.size != 2) null else split[0] to split[1]
        }.toMap()
        email = values["email"]?.let(CampusEmailAddress::normalize)
        emailVerified = values["verified"] == "1" && email != null
        localCredentialToken = values["local"]?.takeIf { CampusCredential.decode(it) != null }
    }

    private fun writeProfile() {
        val body = buildString {
            email?.let { append("email=").append(it).append('\n') }
            append("verified=").append(if (emailVerified) "1" else "0").append('\n')
            localCredentialToken?.let { append("local=").append(it).append('\n') }
        }
        val temporary = File(directory, "profile.txt.tmp")
        temporary.writeText(body)
        if (profileFile.exists() && !profileFile.delete()) {
            profileFile.writeText(body)
            temporary.delete()
            return
        }
        if (!temporary.renameTo(profileFile)) {
            profileFile.writeText(body)
            temporary.delete()
        }
    }
}
