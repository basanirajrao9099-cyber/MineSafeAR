package com.minesafear.certificate

import java.security.MessageDigest

/**
 * Stamps a certificate with a digest of its own contents so a scanner can tell a
 * real card from a hand-typed one without any network access.
 *
 * ## Production note: this is a prototype stand-in, not a signature
 *
 * In production this belongs in a **server-side signed JWT** (or any asymmetric
 * signature): the site's backend holds a private key, signs the certificate
 * claims, and every device verifies with the public key. Only the issuer can then
 * mint a valid card.
 *
 * What this object does instead is hash the certificate fields together with
 * [LOCAL_SIGNING_SALT], a constant compiled into the APK. For the offline
 * prototype that is enough to demonstrate the concept — it detects a card whose
 * details have been edited after issue, which is the demo — but it is **not**
 * authentication. The salt ships inside every install, so anyone who unzips the
 * APK can mint certificates that verify. Note also that a true HMAC
 * (`Mac.getInstance("HmacSHA256")`) would be the correct primitive for a keyed
 * digest and is a drop-in change here; it is not the reason this is weak. The
 * shared secret is. Nothing keyed off a constant in client code can be
 * trustworthy, so treat a VALID result as "this card was produced by MineSafeAR
 * and has not been altered since", never as "this worker is certified" for any
 * decision that matters.
 *
 * Rotating [LOCAL_SIGNING_SALT] invalidates every certificate already issued.
 */
object CertificateSigner {

    /** Length of the hex digest produced by [signature]. */
    const val SIGNATURE_LENGTH_CHARS: Int = 64

    /** Replace with a server-held key before any field deployment. See the class note. */
    private const val LOCAL_SIGNING_SALT = "MineSafeAR/offline-prototype/v1/not-a-real-secret"

    /**
     * ASCII unit separator. Joining the fields with a character that cannot occur
     * in an id, a number or a salt keeps the digest unambiguous: without it,
     * `certId = "ab", userId = "c"` and `certId = "a", userId = "bc"` would hash
     * to the same value and each could be passed off as the other.
     */
    private const val FIELD_SEPARATOR = "\u001F"

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * SHA-256 of `cert_id + user_id + score + issued_date + salt`, lowercase hex.
     *
     * [issuedDate] is epoch millis. Note that the expiry is deliberately *not* an
     * input — it is derived from [issuedDate] by [CertificatePolicy], and
     * [CertificateVerifier] re-derives it rather than trusting the scanned value,
     * which is what stops an expiry from being extended without detection.
     */
    fun signature(certId: String, userId: String, score: Int, issuedDate: Long): String =
        sha256Hex(
            listOf(
                certId,
                userId,
                score.toString(),
                issuedDate.toString(),
                LOCAL_SIGNING_SALT,
            ).joinToString(FIELD_SEPARATOR),
        )

    /**
     * True when [candidateSignature] is the signature these fields should carry.
     *
     * Compared with [MessageDigest.isEqual], which does not short-circuit on the
     * first differing byte. There is no remote timing oracle in an offline
     * scanner, so this is cheap insurance rather than a fix for a known attack —
     * but a hash comparison is exactly the place where `==` becomes a habit worth
     * not forming.
     */
    fun matches(
        certId: String,
        userId: String,
        score: Int,
        issuedDate: Long,
        candidateSignature: String,
    ): Boolean = MessageDigest.isEqual(
        signature(certId, userId, score, issuedDate).toByteArray(Charsets.UTF_8),
        candidateSignature.toByteArray(Charsets.UTF_8),
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }
}
