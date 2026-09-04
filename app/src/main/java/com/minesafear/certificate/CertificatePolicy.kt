package com.minesafear.certificate

/**
 * How long a certificate is good for, and nothing else. Both the issuer and the
 * verifier read the span from here so a card issued on one phone is judged
 * identically on another.
 *
 * ## Why a fixed span and not a calendar year
 *
 * "Issued date + 1 year" is expressed as a fixed number of milliseconds rather
 * than `plusYears(1)` on a local date. Calendar arithmetic depends on the device's
 * time zone and on whether a leap day falls inside the window, so two phones could
 * derive two different expiry instants from the same issue instant — and
 * [CertificateVerifier] re-derives the expiry to detect tampering, so a
 * disagreement there would read as a forged card. A fixed span is reproducible
 * everywhere.
 *
 * The cost is that certificates issued in a leap year lapse a day before the
 * anniversary. That is acceptable for a training credential; if a site's
 * compliance rules require the exact anniversary, the expiry has to become a
 * server-issued value rather than a derived one.
 */
object CertificatePolicy {

    /** Regulatory refresher interval for site safety training. */
    const val VALIDITY_DAYS: Int = 365

    const val VALIDITY_MILLIS: Long = VALIDITY_DAYS * 24L * 60L * 60L * 1000L

    fun expiryFor(issuedDate: Long): Long = issuedDate + VALIDITY_MILLIS

    /**
     * Expiry is exclusive: a certificate is spent the instant it reaches its
     * expiry, not a millisecond later.
     */
    fun isExpiredAt(expiryDate: Long, nowMillis: Long): Boolean = nowMillis >= expiryDate
}
