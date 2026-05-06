package com.crdp.core.rdp.engine

/**
 * Bit values match `com.freerdp.freerdpcore.services.LibFreeRDP` `VERIFY_CERT_FLAG_*`
 * so JNI callbacks can forward [EngineChallenge.Certificate.flags] verbatim.
 */
object VerifyCertificateFlags {
    const val LEGACY: Long = 0x002L
    const val REDIRECT: Long = 0x010L
    const val GATEWAY: Long = 0x020L
    const val CHANGED: Long = 0x040L
    const val MISMATCH: Long = 0x080L
    const val MATCH_LEGACY_SHA1: Long = 0x100L
    const val FP_IS_PEM: Long = 0x200L
}

object CertificateVerifyMessages {

    private const val UNTRUSTED =
        "The certificate is not from a trusted certifying authority."

    /**
     * User-visible lines for the "Certificate errors" section. Order follows
     * typical severity: name issues, then change, gateway/redirect, legacy details.
     */
    fun certificateErrorMessages(flags: Long): List<String> {
        val messages = ArrayList<String>()
        if (flags and VerifyCertificateFlags.MISMATCH != 0L) {
            messages += "The certificate name does not match the remote computer."
        }
        if (flags and VerifyCertificateFlags.CHANGED != 0L) {
            messages += "The certificate has changed since the last connection."
        }
        if (flags and VerifyCertificateFlags.GATEWAY != 0L) {
            messages += "The Remote Desktop Gateway certificate could not be verified in the usual way."
        }
        if (flags and VerifyCertificateFlags.REDIRECT != 0L) {
            messages += "A certificate was presented while being redirected to another server."
        }
        if (flags and VerifyCertificateFlags.LEGACY != 0L) {
            messages += "The server is using a legacy certificate representation."
        }
        if (flags and VerifyCertificateFlags.MATCH_LEGACY_SHA1 != 0L) {
            messages += "The certificate was matched using a legacy SHA-1 fingerprint."
        }
        if (messages.isEmpty()) {
            messages += UNTRUSTED
        }
        return messages
    }

    fun isPemFingerprint(flags: Long): Boolean =
        flags and VerifyCertificateFlags.FP_IS_PEM != 0L
}
