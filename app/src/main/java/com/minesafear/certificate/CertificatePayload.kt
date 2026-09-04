package com.minesafear.certificate

/**
 * What gets encoded into a certificate's QR code: everything a verifier needs to
 * recompute the signature and judge the card with no network and no local copy of
 * the certificate.
 *
 * A delimited string is used rather than JSON. It is roughly half the bytes for
 * the same fields, which keeps the QR at a low enough version to stay scannable
 * from a scuffed printed card, and it needs no JSON dependency.
 *
 * ## Why [score] is here
 *
 * The signature covers `cert_id + user_id + score + issued_date` (see
 * [CertificateSigner]), so a verifier cannot recompute it without the score.
 * Encoding the other four fields alone would make the QR unverifiable by anyone
 * who does not already hold the certificate — which is every case that matters.
 *
 * ## What is deliberately absent
 *
 * The holder's name and the module list are not encoded. Neither is covered by the
 * signature, so carrying them would only offer a forger a free text field, and a
 * scannable code that publishes a worker's name is a needless privacy leak. The
 * verify screen shows the name only when the certificate is also in the local
 * database.
 */
data class CertificatePayload(
    val certId: String,
    val userId: String,
    val score: Int,
    /** Epoch millis. */
    val issuedDate: Long,
    /** Epoch millis. Always [CertificatePolicy.expiryFor] of [issuedDate]. */
    val expiryDate: Long,
    val signatureHash: String,
) {
    fun encode(): String = listOf(
        PREFIX,
        certId,
        userId,
        score.toString(),
        issuedDate.toString(),
        expiryDate.toString(),
        signatureHash,
    ).joinToString(SEPARATOR)

    companion object {
        /**
         * Versioned so a future payload shape can be told apart on sight. Bumped
         * from `MSAR1`, which carried a per-module serial number instead of a
         * per-worker certificate id; an old card now fails to parse rather than
         * being misread field for field.
         */
        const val PREFIX = "MSAR2"

        private const val SEPARATOR = "|"
        private const val FIELD_COUNT = 7
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /**
         * Returns null for anything that is not a well-formed MineSafeAR payload.
         * Scanners see plenty of unrelated codes, so a malformed value is expected
         * input, not an error.
         *
         * Parsing says nothing about authenticity — every field here is
         * attacker-controlled. Run the result through [CertificateVerifier] before
         * showing it as a certificate.
         */
        fun parse(raw: String): CertificatePayload? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != FIELD_COUNT || parts[0] != PREFIX) return null
            return CertificatePayload(
                certId = parts[1].ifBlank { return null },
                userId = parts[2].ifBlank { return null },
                score = parts[3].toIntOrNull() ?: return null,
                issuedDate = parts[4].toLongOrNull() ?: return null,
                expiryDate = parts[5].toLongOrNull() ?: return null,
                // Rejected early so a malformed code reads as "not a certificate"
                // rather than as a certificate that failed its signature check.
                signatureHash = parts[6].takeIf { SHA256_HEX.matches(it) } ?: return null,
            )
        }
    }
}
