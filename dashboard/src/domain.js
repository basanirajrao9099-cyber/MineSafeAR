/**
 * The Android certificate logic, ported.
 *
 * Every constant and every rule in this file is copied from the app so that a
 * certificate judged VALID here is judged VALID on a phone and vice versa. The
 * sources, all under `app/src/main/java/com/minesafear/`:
 *
 *   certificate/CertificateSigner.kt    signature(), the salt, the separator
 *   certificate/CertificatePolicy.kt    validity span, expiryFor, isExpiredAt
 *   certificate/CertificatePayload.kt   encode/parse, the MSAR2 wire format
 *   certificate/CertificateVerifier.kt  the order the checks run in
 *   certificate/CertificateIssuer.kt    best-attempt-per-module, floored average
 *
 * If any of those change, this file is wrong until it changes too. There is no
 * build step that would tell you.
 */

// --- Signing ------------------------------------------------------------------

/**
 * The same constant that ships in the APK, which is exactly the problem with it.
 *
 * Anyone reading this file can mint a certificate that verifies. A VALID verdict
 * therefore means "produced by MineSafeAR and unaltered since", never "this
 * worker is authorised" — see the production note on CertificateSigner.kt. The
 * Verify page says so on screen for the same reason.
 */
export const SALT = 'MineSafeAR/offline-prototype/v1/not-a-real-secret'

/**
 * ASCII unit separator. Joining with a character that cannot occur in an id, a
 * number or the salt keeps the digest unambiguous: without it, certId `ab` with
 * userId `c` and certId `a` with userId `bc` would hash identically.
 */
export const FIELD_SEPARATOR = '\u001f'

export const VALIDITY_DAYS = 365
export const VALIDITY_MS = VALIDITY_DAYS * 24 * 60 * 60 * 1000

/** ScoringEngine.PASS_THRESHOLD_PERCENT, and CertificateIssuer's threshold. */
export const PASS_THRESHOLD = 80

/** The Overview's warning horizon. A dashboard choice, not an app constant. */
export const EXPIRING_SOON_DAYS = 30

export const DAY_MS = 24 * 60 * 60 * 1000

const HEX = Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'))

/**
 * SHA-256 of certId + userId + score + issuedDate + salt, lowercase hex.
 *
 * Async because `crypto.subtle` is, which is why every caller of this file is
 * async too. `crypto.subtle` needs a secure context: localhost counts, a
 * `file://` page does not — open the dashboard through the dev server.
 *
 * The expiry is deliberately not an input. It is derived from issuedDate, and
 * verification re-derives it rather than trusting what it was handed, which is
 * what makes a stretched expiry detectable.
 */
export async function signature(certId, userId, score, issuedDate) {
  const joined = [certId, userId, String(score), String(issuedDate), SALT].join(FIELD_SEPARATOR)
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(joined))
  return Array.from(new Uint8Array(digest), (byte) => HEX[byte]).join('')
}

export function expiryFor(issuedDate) {
  return issuedDate + VALIDITY_MS
}

/** Expiry is exclusive: a certificate is spent the instant it reaches it. */
export function isExpiredAt(expiryDate, now) {
  return now >= expiryDate
}

// --- The MSAR2 payload --------------------------------------------------------

export const PAYLOAD_PREFIX = 'MSAR2'
const PAYLOAD_SEPARATOR = '|'
const PAYLOAD_FIELD_COUNT = 7
const SHA256_HEX = /^[0-9a-f]{64}$/

/** What the QR code on a worker's certificate screen contains. */
export function encodePayload(cert) {
  return [
    PAYLOAD_PREFIX,
    cert.certId,
    cert.userId,
    String(cert.score),
    String(cert.issuedDate),
    String(cert.expiryDate),
    cert.signatureHash,
  ].join(PAYLOAD_SEPARATOR)
}

/**
 * Returns null for anything that is not a well-formed payload. A malformed value
 * is expected input, not an error — a scanner sees plenty of unrelated codes, and
 * an admin pastes plenty of wrong things.
 *
 * Parsing says nothing about authenticity. Every field here is supplied by
 * whoever holds the card.
 */
export function parsePayload(raw) {
  const parts = String(raw ?? '').trim().split(PAYLOAD_SEPARATOR)
  if (parts.length !== PAYLOAD_FIELD_COUNT || parts[0] !== PAYLOAD_PREFIX) return null

  const [, certId, userId, score, issuedDate, expiryDate, signatureHash] = parts
  if (!certId || !userId) return null
  if (!/^-?\d+$/.test(score) || !/^\d+$/.test(issuedDate) || !/^\d+$/.test(expiryDate)) return null
  if (!SHA256_HEX.test(signatureHash)) return null

  return {
    certId,
    userId,
    score: Number(score),
    issuedDate: Number(issuedDate),
    expiryDate: Number(expiryDate),
    signatureHash,
  }
}

// --- Verification -------------------------------------------------------------

export const VERDICT = {
  VALID: 'VALID',
  EXPIRED: 'EXPIRED',
  SIGNATURE_MISMATCH: 'SIGNATURE_MISMATCH',
  EXPIRY_ALTERED: 'EXPIRY_ALTERED',
  NOT_A_CERTIFICATE: 'NOT_A_CERTIFICATE',
  /** Not an Android verdict: a phone has no record set to be absent from. */
  NOT_ON_RECORD: 'NOT_ON_RECORD',
}

/** Plain-language copy, one line per verdict. No "Oops", no error codes alone. */
export const VERDICT_COPY = {
  [VERDICT.VALID]:
    'The signature matches and the certificate is in date.',
  [VERDICT.EXPIRED]:
    'The signature matches, so this certificate is genuine — but it has passed its expiry date and no longer counts as current training.',
  [VERDICT.SIGNATURE_MISMATCH]:
    'The signature does not match these details. One of the signed fields — certificate id, worker id, score or issue date — has been changed since the certificate was issued.',
  [VERDICT.EXPIRY_ALTERED]:
    'The signature matches, but the expiry date is not 365 days after the issue date. The expiry is not covered by the signature, so it has to be re-derived — and this one has been edited.',
  [VERDICT.NOT_A_CERTIFICATE]:
    'This is not a MineSafeAR certificate payload. Expected seven pipe-separated fields beginning with MSAR2.',
  [VERDICT.NOT_ON_RECORD]:
    'No certificate with this id has been synced to the register. It may have been issued on a phone that has not uploaded yet, or the id may be wrong.',
}

/**
 * Whether a verdict means the holder can be treated as currently trained.
 * Only VALID does. EXPIRED is genuine but spent.
 */
export function isPass(verdict) {
  return verdict === VERDICT.VALID
}

/**
 * The Android verifier, run against one certificate record.
 *
 * Same order as CertificateVerifier.verify: signature first, then whether the
 * stored expiry is the derived one, then whether it has passed. The order
 * matters — an altered expiry on a card whose signature is already broken should
 * report the broken signature, because that is the more fundamental problem.
 */
export async function verifyRecord(cert, now) {
  const expected = await signature(cert.certId, cert.userId, cert.score, cert.issuedDate)

  if (expected !== cert.signatureHash) {
    return { verdict: VERDICT.SIGNATURE_MISMATCH, expected }
  }
  if (cert.expiryDate !== expiryFor(cert.issuedDate)) {
    return { verdict: VERDICT.EXPIRY_ALTERED, expected }
  }
  if (isExpiredAt(cert.expiryDate, now)) {
    return { verdict: VERDICT.EXPIRED, expected }
  }
  return { verdict: VERDICT.VALID, expected }
}

/**
 * Verifies every synced certificate once, at load.
 *
 * The dashboard can do this and a phone cannot: it holds the whole register, so
 * it can audit the set rather than waiting for someone to scan a card. Returns a
 * plain object keyed by certId.
 */
export async function checkIntegrity(workers, now) {
  const entries = await Promise.all(
    workers
      .filter((worker) => worker.certificate)
      .map(async (worker) => [
        worker.certificate.certId,
        await verifyRecord(worker.certificate, now),
      ]),
  )
  return Object.fromEntries(entries)
}

/**
 * What the Verify page runs.
 *
 * Two ways in, because an admin has two things to hand:
 *
 * - A full `MSAR2|…` payload, off a worker's screen or a scanned card. Verified
 *   self-contained, exactly as the phone would, then cross-checked against the
 *   register so a card that verifies but was never synced is still reported.
 * - A certificate id and a signature hash typed in separately. The other signed
 *   fields have to come from the register, so this mode reports NOT_ON_RECORD
 *   when the id is unknown — there is nothing to verify against.
 */
export async function verifySubmission({ certId, signatureHash }, workers, now) {
  const raw = String(certId ?? '').trim()
  const payload = parsePayload(raw)

  if (payload) {
    const result = await verifyRecord(payload, now)
    const worker = findWorkerByCertId(workers, payload.certId)
    return {
      mode: 'payload',
      verdict: result.verdict,
      submittedHash: payload.signatureHash,
      expectedHash: result.expected,
      cert: payload,
      worker,
      onRecord: Boolean(worker),
      recordMatches: worker
        ? sameSignedFields(worker.certificate, payload)
        : null,
    }
  }

  // Not a payload, so it has to be an id plus a hash.
  if (looksLikePayloadAttempt(raw)) {
    return {
      mode: 'payload',
      verdict: VERDICT.NOT_A_CERTIFICATE,
      submittedHash: null,
      expectedHash: null,
      cert: null,
      worker: null,
      onRecord: false,
      recordMatches: null,
    }
  }

  const submittedHash = String(signatureHash ?? '').trim().toLowerCase()
  const worker = findWorkerByCertId(workers, raw)

  if (!worker) {
    return {
      mode: 'fields',
      verdict: VERDICT.NOT_ON_RECORD,
      submittedHash: submittedHash || null,
      expectedHash: null,
      cert: null,
      worker: null,
      onRecord: false,
      recordMatches: null,
    }
  }

  const cert = worker.certificate
  const expectedHash = await signature(cert.certId, cert.userId, cert.score, cert.issuedDate)

  let verdict
  if (submittedHash !== expectedHash) {
    verdict = VERDICT.SIGNATURE_MISMATCH
  } else if (cert.expiryDate !== expiryFor(cert.issuedDate)) {
    verdict = VERDICT.EXPIRY_ALTERED
  } else if (isExpiredAt(cert.expiryDate, now)) {
    verdict = VERDICT.EXPIRED
  } else {
    verdict = VERDICT.VALID
  }

  return {
    mode: 'fields',
    verdict,
    submittedHash: submittedHash || null,
    expectedHash,
    cert,
    worker,
    onRecord: true,
    recordMatches: true,
  }
}

/** A pipe in the input means they meant to paste a payload and it is malformed. */
function looksLikePayloadAttempt(raw) {
  return raw.includes('|') || raw.toUpperCase().startsWith('MSAR')
}

function findWorkerByCertId(workers, certId) {
  return workers.find((worker) => worker.certificate?.certId === certId) ?? null
}

function sameSignedFields(a, b) {
  return (
    a.certId === b.certId &&
    a.userId === b.userId &&
    a.score === b.score &&
    a.issuedDate === b.issuedDate
  )
}

// --- Aggregation --------------------------------------------------------------

/**
 * Best attempt per module. Mirrors CertificateIssuer, which scores a worker on
 * their best run rather than their latest — a drill is training, and the point is
 * that they got there, not that they got there first time.
 */
export function bestScores(results) {
  const best = new Map()
  for (const result of results) {
    const current = best.get(result.moduleId)
    if (current === undefined || result.score > current) best.set(result.moduleId, result.score)
  }
  return best
}

/** Floored integer mean, as CertificateIssuer computes it. 0 for no attempts. */
export function averageScore(scores) {
  if (scores.length === 0) return 0
  return Math.floor(scores.reduce((sum, score) => sum + score, 0) / scores.length)
}

export const STATUS = {
  CERTIFIED: 'CERTIFIED',
  EXPIRING: 'EXPIRING',
  EXPIRED: 'EXPIRED',
  /** Certificate on file that fails its own integrity check. */
  FLAGGED: 'FLAGGED',
  IN_PROGRESS: 'IN_PROGRESS',
  NOT_STARTED: 'NOT_STARTED',
}

/** Display order, worst-first where it matters — drives the band and the legend. */
export const STATUS_ORDER = [
  STATUS.CERTIFIED,
  STATUS.EXPIRING,
  STATUS.EXPIRED,
  STATUS.FLAGGED,
  STATUS.IN_PROGRESS,
  STATUS.NOT_STARTED,
]

export const STATUS_LABEL = {
  [STATUS.CERTIFIED]: 'Certified',
  [STATUS.EXPIRING]: 'Expiring',
  [STATUS.EXPIRED]: 'Lapsed',
  [STATUS.FLAGGED]: 'Flagged',
  [STATUS.IN_PROGRESS]: 'In training',
  [STATUS.NOT_STARTED]: 'Not started',
}

/**
 * A worker's standing, derived from the certificate on record rather than
 * re-decided from their scores.
 *
 * This is deliberate. The app's CertificateIssuer certifies on an average of the
 * modules a worker actually attempted, so it will issue a certificate to someone
 * who has done one module well. Re-applying a stricter rule here would let the
 * dashboard call a worker uncertified while they hold a certificate the app
 * really issued, and the register would be arguing with itself. So the signed
 * record is the fact, and incomplete syllabus coverage is reported separately —
 * see `coverage`.
 *
 * FLAGGED outranks everything: a certificate that fails its own signature check
 * is not evidence of training, whatever its dates say.
 */
export function statusOf(worker, integrity, now) {
  const cert = worker.certificate
  if (!cert) {
    return worker.moduleResults.length > 0 ? STATUS.IN_PROGRESS : STATUS.NOT_STARTED
  }

  const verdict = integrity?.[cert.certId]?.verdict
  if (verdict === VERDICT.SIGNATURE_MISMATCH || verdict === VERDICT.EXPIRY_ALTERED) {
    return STATUS.FLAGGED
  }
  if (isExpiredAt(cert.expiryDate, now)) return STATUS.EXPIRED
  if (cert.expiryDate - now <= EXPIRING_SOON_DAYS * DAY_MS) return STATUS.EXPIRING
  return STATUS.CERTIFIED
}

/**
 * Which required modules a worker has passed, and which they have not.
 *
 * The advisory the app cannot currently give: CertificateIssuer has no notion of
 * a required set, so a certificate says nothing about syllabus coverage. Shown on
 * the worker detail rather than folded into the status, because it is a policy
 * question and the status is a matter of record.
 */
export function coverage(worker, modules) {
  const best = bestScores(worker.moduleResults)
  const required = modules.filter((module) => module.required)
  const missing = required.filter((module) => (best.get(module.id) ?? 0) < PASS_THRESHOLD)
  return { required, missing, complete: missing.length === 0 }
}

/** Everything the Overview shows, in one pass over the register. */
export function summarise(workers, integrity, modules, now) {
  const counts = Object.fromEntries(STATUS_ORDER.map((status) => [status, 0]))
  const scores = []
  let trained = 0

  for (const worker of workers) {
    counts[statusOf(worker, integrity, now)] += 1
    if (worker.moduleResults.length > 0) {
      trained += 1
      scores.push(averageScore([...bestScores(worker.moduleResults).values()]))
    }
  }

  const verdicts = Object.values(integrity ?? {})
  const holdsCurrent = counts[STATUS.CERTIFIED] + counts[STATUS.EXPIRING]

  return {
    total: workers.length,
    trained,
    counts,
    certifiedPercent: workers.length === 0
      ? 0
      : Math.round((holdsCurrent / workers.length) * 100),
    averageScore: averageScore(scores),
    expiringSoon: counts[STATUS.EXPIRING],
    siteCount: new Set(workers.map((worker) => worker.siteId)).size,
    moduleCount: modules.filter((module) => module.status === 'live').length,
    integrity: {
      checked: verdicts.length,
      failed: verdicts.filter((entry) => entry.verdict === VERDICT.SIGNATURE_MISMATCH
        || entry.verdict === VERDICT.EXPIRY_ALTERED).length,
    },
  }
}

/** Per-site rollup for the Overview's second table. */
export function bySite(workers, sites, integrity, now) {
  return sites
    .map((site) => {
      const cohort = workers.filter((worker) => worker.siteId === site.id)
      const counts = Object.fromEntries(STATUS_ORDER.map((status) => [status, 0]))
      for (const worker of cohort) counts[statusOf(worker, integrity, now)] += 1
      const current = counts[STATUS.CERTIFIED] + counts[STATUS.EXPIRING]
      return {
        site,
        counts,
        workers: cohort.length,
        percent: cohort.length === 0 ? 0 : Math.round((current / cohort.length) * 100),
      }
    })
    .sort((a, b) => a.percent - b.percent)
}

// --- Formatting ---------------------------------------------------------------

const DATE_FORMAT = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric', month: 'short', year: 'numeric',
})

export function formatDate(millis) {
  return millis == null ? '—' : DATE_FORMAT.format(new Date(millis))
}

export function formatDuration(seconds) {
  const minutes = Math.floor(seconds / 60)
  return minutes === 0 ? `${seconds}s` : `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`
}

/** "in 12 days" / "18 days ago". Blunt on purpose; this is a compliance clock. */
export function relativeDays(millis, now) {
  const days = Math.round((millis - now) / DAY_MS)
  if (days === 0) return 'today'
  if (days > 0) return `in ${days} day${days === 1 ? '' : 's'}`
  return `${-days} day${days === -1 ? '' : 's'} ago`
}
