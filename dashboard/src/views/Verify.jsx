import React, { useEffect, useMemo, useState } from 'react'
import {
  VERDICT,
  VERDICT_COPY,
  encodePayload,
  expiryFor,
  formatDate,
  isPass,
  relativeDays,
  verifySubmission,
} from '../domain.js'
import { Field, HashCompare } from '../components.jsx'

/**
 * The Android verification screen, minus the camera.
 *
 * A phone scans a QR and checks the payload against itself. An admin has neither a
 * camera pointed at a card nor patience for sixty-four hex characters, so this takes
 * two routes to the same verdict: paste the whole `MSAR2|…` payload, or type a
 * certificate id and the signature hash printed on the card. Both end up in
 * `verifySubmission`, which runs the checks in the order CertificateVerifier does.
 */
export default function Verify({ workers, integrity, now, index, go, prefill }) {
  const [certId, setCertId] = useState(prefill?.certId ?? '')
  const [hash, setHash] = useState(prefill?.signatureHash ?? '')
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)

  /**
   * One button per verdict worth showing, so the demo needs no typing.
   *
   * Picked by asking the load-time integrity audit rather than by guessing from the
   * dates — the record with the edited score still has a consistent expiry, so a
   * date-based guess would offer it as the genuine example.
   */
  const examples = useMemo(() => {
    const pick = (wanted) => workers.find(
      (worker) => worker.certificate && integrity?.[worker.certificate.certId]?.verdict === wanted,
    )
    return [
      { label: 'Load a valid one', worker: pick(VERDICT.VALID) },
      { label: 'Load an edited score', worker: pick(VERDICT.SIGNATURE_MISMATCH) },
      { label: 'Load a stretched expiry', worker: pick(VERDICT.EXPIRY_ALTERED) },
    ].filter((example) => example.worker)
  }, [workers, integrity])

  // Verify whatever arrived pre-filled, so arriving from a worker's certificate
  // shows a verdict rather than a form waiting to be submitted again.
  useEffect(() => {
    if (prefill?.certId) run(prefill.certId, prefill.signatureHash ?? '')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function run(idValue, hashValue) {
    setBusy(true)
    try {
      setResult(await verifySubmission(
        { certId: idValue, signatureHash: hashValue },
        workers,
        now,
      ))
    } finally {
      setBusy(false)
    }
  }

  const submit = (event) => {
    event.preventDefault()
    run(certId, hash)
  }

  const load = (worker) => {
    const payload = encodePayload(worker.certificate)
    setCertId(payload)
    setHash('')
    run(payload, '')
  }

  const clear = () => {
    setCertId('')
    setHash('')
    setResult(null)
  }

  return (
    <div className="verify">
      <div className="page-head">
        <h1>Verify certificate</h1>
      </div>

      <form className="card verify-form" onSubmit={submit}>
        <Field
          label="Certificate id, or a full scanned payload"
          hint="Paste the whole MSAR2|… string off a worker's certificate screen and it is split and checked on its own, exactly as the phone would. A bare id is checked against the synced register instead."
        >
          <textarea
            className="mono"
            rows={3}
            value={certId}
            spellCheck={false}
            placeholder="MSAR2|… or 9e4d3acc-6194-46ac-9d1c-457c6fc9de06"
            onChange={(event) => setCertId(event.target.value)}
          />
        </Field>

        <Field
          label="Signature hash"
          hint="Only needed when you entered a bare certificate id — a pasted payload carries its own."
        >
          <input
            type="text"
            className="mono"
            value={hash}
            spellCheck={false}
            placeholder="64 hexadecimal characters"
            onChange={(event) => setHash(event.target.value)}
          />
        </Field>

        <div className="btn-row">
          <button type="submit" className="btn" disabled={busy || certId.trim() === ''}>
            {busy ? 'Checking…' : 'Check certificate'}
          </button>
          {examples.map((example) => (
            <button
              key={example.label}
              type="button"
              className="btn ghost"
              onClick={() => load(example.worker)}
            >
              {example.label}
            </button>
          ))}
          {result ? (
            <button type="button" className="btn quiet" onClick={clear}>Clear</button>
          ) : null}
        </div>
      </form>

      {result ? <Verdict result={result} now={now} index={index} go={go} /> : null}

      <p className="caveat">
        <strong>What a valid result does and does not mean</strong>
        The signature is a SHA-256 over the certificate id, worker id, score and issue
        date, salted with a constant that ships inside the app — so it proves a record
        was produced by MineSafeAR and has not been edited since. It is not proof that
        the holder is authorised, because anyone with a copy of the app can compute the
        same hash. A real deployment signs server-side and verifies against a key the
        phone never holds; the offline prototype demonstrates the mechanism, not the
        trust model.
      </p>
    </div>
  )
}

function Verdict({ result, now, index, go }) {
  const { verdict, cert, worker, mode, onRecord, recordMatches } = result

  const tone = isPass(verdict)
    ? 'is-pass'
    : verdict === VERDICT.EXPIRED
      ? 'is-warn'
      : 'is-fail'

  return (
    <section className={`verdict ${tone}`} aria-live="polite">
      <p className="verdict-word">{verdict.replace(/_/g, ' ')}</p>
      <p className="verdict-copy">{VERDICT_COPY[verdict]}</p>

      {cert ? (
        <>
          <dl className="pairs" style={{ marginTop: 18 }}>
            <div>
              <dt>Worker</dt>
              <dd>
                {worker ? (
                  <button type="button" className="cell-btn" onClick={() => go('worker', { workerId: worker.workerId })}>
                    {worker.fullName}
                  </button>
                ) : (
                  <span className="muted">not on the register</span>
                )}
              </dd>
            </div>
            <div>
              <dt>Site</dt>
              <dd>{worker ? (index.siteById.get(worker.siteId)?.shortName ?? '—') : '—'}</dd>
            </div>
            <div>
              <dt>Score</dt>
              <dd>{cert.score} / 100</dd>
            </div>
            <div>
              <dt>Issued</dt>
              <dd>{formatDate(cert.issuedDate)}</dd>
            </div>
            <div>
              <dt>Expiry on the card</dt>
              <dd>
                {formatDate(cert.expiryDate)}{' '}
                <span className="muted">({relativeDays(cert.expiryDate, now)})</span>
              </dd>
            </div>
            <div>
              <dt>Expiry re-derived</dt>
              <dd>
                {formatDate(expiryFor(cert.issuedDate))}
                {cert.expiryDate === expiryFor(cert.issuedDate)
                  ? null
                  : <span className="muted"> — does not match</span>}
              </dd>
            </div>
          </dl>

          <HashCompare submitted={result.submittedHash} expected={result.expectedHash} />

          {/*
            A payload can be perfectly signed and still be something the register has
            never seen — a certificate from a phone that has not synced, or one minted
            outside the app entirely. The phone cannot tell those apart; this can.
          */}
          {mode === 'payload' && !onRecord ? (
            <p className="hint" style={{ marginTop: 14 }}>
              This certificate id has not been synced to the register. The verdict above
              is the card checked against itself, which is all a phone can do.
            </p>
          ) : null}
          {mode === 'payload' && onRecord && recordMatches === false ? (
            <p className="hint" style={{ marginTop: 14 }}>
              A certificate with this id is on the register, but its signed fields differ
              from the ones pasted here. Two versions of the same certificate exist.
            </p>
          ) : null}
        </>
      ) : (
        <HashCompare submitted={result.submittedHash} expected={result.expectedHash} />
      )}
    </section>
  )
}
