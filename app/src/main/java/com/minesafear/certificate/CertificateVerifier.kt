package com.minesafear.certificate

/**
 * The outcome of checking a scanned QR code. [payload] is present whenever the
 * code parsed at all, so the verify screen can show what was on a rejected card.
 */
sealed interface CertificateVerification {

    val payload: CertificatePayload?

    /** Signature matches and the certificate has not lapsed. */
    data class Valid(override val payload: CertificatePayload) : CertificateVerification

    /** Signature matches; the validity window has passed. Retraining is due. */
    data class Expired(override val payload: CertificatePayload) : CertificateVerification

    data class Invalid(
        override val payload: CertificatePayload?,
        val reason: Reason,
    ) : CertificateVerification {

        enum class Reason {
            /** Not a MineSafeAR code, or a malformed one. */
            NOT_A_CERTIFICATE,

            /** One of the signed fields has been changed since issue. */
            SIGNATURE_MISMATCH,

            /** The signed fields are intact but the expiry date has been moved. */
            EXPIRY_ALTERED,
        }
    }
}

/**
 * Judges a scanned certificate offline.
 *
 * Everything needed is in the code itself, so a supervisor underground with no
 * signal gets the same answer as one at the surface. Read [CertificateSigner]'s
 * production note before trusting a VALID result for anything consequential: the
 * signing secret ships in the APK, so this proves a card is unaltered, not that
 * its issuer was authorised.
 */
object CertificateVerifier {

    /**
     * @param nowMillis passed in rather than read from the clock so the expiry
     *   boundary is testable and so one scan cannot be judged against two
     *   different "now"s.
     */
    fun verify(scannedContents: String?, nowMillis: Long): CertificateVerification {
        val payload = scannedContents?.let(CertificatePayload::parse)
            ?: return CertificateVerification.Invalid(
                payload = null,
                reason = CertificateVerification.Invalid.Reason.NOT_A_CERTIFICATE,
            )

        val signed = CertificateSigner.matches(
            certId = payload.certId,
            userId = payload.userId,
            score = payload.score,
            issuedDate = payload.issuedDate,
            candidateSignature = payload.signatureHash,
        )
        if (!signed) {
            return CertificateVerification.Invalid(
                payload = payload,
                reason = CertificateVerification.Invalid.Reason.SIGNATURE_MISMATCH,
            )
        }

        // The expiry is not one of the signed fields, so on its own it could be
        // edited to keep a lapsed card alive. It does not have to be signed,
        // because it is derived: the issue date *is* signed, and the validity span
        // is fixed, so the only legitimate expiry is the one recomputed here. Any
        // other value means the code was edited.
        if (payload.expiryDate != CertificatePolicy.expiryFor(payload.issuedDate)) {
            return CertificateVerification.Invalid(
                payload = payload,
                reason = CertificateVerification.Invalid.Reason.EXPIRY_ALTERED,
            )
        }

        return if (CertificatePolicy.isExpiredAt(payload.expiryDate, nowMillis)) {
            CertificateVerification.Expired(payload)
        } else {
            CertificateVerification.Valid(payload)
        }
    }
}
