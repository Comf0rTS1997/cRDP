package com.crdp.core.rdp.engine

sealed class EngineChallenge {
    abstract val id: String

    data class Certificate(
        override val id: String,
        val host: String,
        val port: Int,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
    ) : EngineChallenge()

    data class CertificateChanged(
        override val id: String,
        val host: String,
        val port: Int,
        val commonName: String,
        val subject: String,
        val issuer: String,
        val fingerprint: String,
        val oldSubject: String,
        val oldIssuer: String,
        val oldFingerprint: String,
    ) : EngineChallenge()

    data class Auth(
        override val id: String,
        val title: String,
        val usernameHint: String = "",
        val domainHint: String? = null,
    ) : EngineChallenge()
}

sealed class ChallengeResponse {
    data object AcceptOnce : ChallengeResponse()
    data object AcceptAlways : ChallengeResponse()
    data object Reject : ChallengeResponse()
    data class Credentials(
        val username: String,
        val password: String,
        val domain: String? = null,
    ) : ChallengeResponse()
}
