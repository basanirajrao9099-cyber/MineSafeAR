import React, { useState } from 'react'
import {
  PASS_THRESHOLD,
  VERDICT,
  VERDICT_COPY,
  averageScore,
  bestScores,
  coverage,
  encodePayload,
  formatDate,
  formatDuration,
  relativeDays,
  statusOf,
} from '../domain.js'
import { Empty, ScoreBar, StatusTag } from '../components.jsx'

const LANGUAGE = { en: 'English', hi: 'Hindi', sat: 'Santali' }

export default function WorkerDetail({ workers, modules, integrity, now, index, go, workerId }) {
  const worker = index.workerById.get(workerId)

  if (!worker) {
    return (
      <Empty
        title="Worker not found"
        action={
          <button type="button" className="btn" onClick={() => go('workers')}>
            Back to workers
          </button>
        }
      >
        No record with that id is in the register.
      </Empty>
    )
  }

  const site = index.siteById.get(worker.siteId)
  const best = bestScores(worker.moduleResults)
  const status = statusOf(worker, integrity, now)
  const { missing } = coverage(worker, modules)
  const overall = worker.moduleResults.length > 0 ? averageScore([...best.values()]) : null

  return (
    <>
      <div className="page-head">
        <h1>Worker record</h1>
        <button type="button" className="btn quiet" onClick={() => go('workers')}>
          ← All workers
        </button>
      </div>

      <div className="detail">
        <div>
          <section className="card identity">
            <h2>{worker.fullName}</h2>
            <p className="role">{worker.jobRole} · {site?.name ?? 'Unknown site'}</p>

            <dl className="pairs">
              <div>
                <dt>Standing</dt>
                <dd><StatusTag status={status} /></dd>
              </div>
              <div>
                <dt>Employee code</dt>
                <dd>{worker.employeeCode}</dd>
              </div>
              <div>
                <dt>Best average</dt>
                <dd>{overall == null ? '—' : `${overall} / 100`}</dd>
              </div>
              <div>
                <dt>Drill attempts</dt>
                <dd>{worker.moduleResults.length}</dd>
              </div>
              <div>
                <dt>App language</dt>
                <dd>{LANGUAGE[worker.preferredLanguage] ?? worker.preferredLanguage}</dd>
              </div>
              <div>
                <dt>Worker id</dt>
                <dd>{worker.workerId}</dd>
              </div>
            </dl>

            {/*
              The gap between what the app enforces and what a syllabus requires.
              CertificateIssuer averages whatever modules a worker attempted, so it
              will certify someone who has done one drill well — this says so out
              loud rather than letting the green tag imply full coverage.
            */}
            {missing.length > 0 ? (
              <p className="advisory">
                <strong>Syllabus not complete</strong>
                {missing.length === 1
                  ? `${missing[0].name} has not been passed. `
                  : `${missing.length} required modules have not been passed: ${missing.map((m) => m.name).join(', ')}. `}
                Certification is issued on the average of the modules a worker has
                attempted, so a certificate here does not imply the full syllabus.
              </p>
            ) : null}
          </section>

          <section className="card modules" aria-labelledby="breakdown">
            <h2 className="section-head" style={{ margin: 0, padding: '14px 20px 0' }} id="breakdown">
              Module breakdown
            </h2>
            {modules.map((module) => (
              <ModuleRow
                key={module.id}
                module={module}
                score={best.get(module.id) ?? null}
                attempts={worker.moduleResults
                  .filter((result) => result.moduleId === module.id)
                  .sort((a, b) => b.timestamp - a.timestamp)}
              />
            ))}
          </section>
        </div>

        <Counterfoil worker={worker} modules={modules} integrity={integrity} now={now} go={go} />
      </div>
    </>
  )
}

function ModuleRow({ module, score, attempts }) {
  const planned = module.status !== 'live'
  const failed = score != null && score < PASS_THRESHOLD
  const bestAttempt = attempts.reduce(
    (top, attempt) => (top == null || attempt.score > top.score ? attempt : top),
    null,
  )

  return (
    <div className={planned ? 'module-row is-planned' : 'module-row'}>
      <div className="module-top">
        <span className="module-name">
          {module.name}
          {module.required ? null : <em>Optional</em>}
          {planned ? <em>Not built yet</em> : null}
        </span>
        {score == null ? (
          <span className="module-score is-none">
            {planned ? 'Not in the app' : 'Not attempted'}
          </span>
        ) : (
          <span className={failed ? 'module-score is-fail' : 'module-score'}>{score}</span>
        )}
      </div>

      {score == null ? null : <ScoreBar score={score} />}

      <p className="module-meta" style={{ margin: score == null ? '6px 0 0' : 0 }}>
        {score == null ? (
          <span>
            {planned
              ? 'This module is in the catalogue but not yet shipped in the APK.'
              : 'No drill recorded for this worker.'}
          </span>
        ) : (
          <>
            <span>{attempts.length} attempt{attempts.length === 1 ? '' : 's'}</span>
            <span>best run {formatDuration(bestAttempt.durationSeconds)}</span>
            <span>{bestAttempt.correctTaps} correct · {bestAttempt.incorrectTaps} incorrect</span>
            <span>last {formatDate(attempts[0].timestamp)}</span>
            <span>pass mark {PASS_THRESHOLD}</span>
          </>
        )}
      </p>
    </div>
  )
}

/**
 * The certificate as a document, not a data row — a training card with a tear-off
 * stub, because that is the object an inspector is holding when they ask about it.
 */
function Counterfoil({ worker, modules, integrity, now, go }) {
  const cert = worker.certificate
  const [copied, setCopied] = useState(false)

  if (!cert) {
    return (
      <section className="card counterfoil" aria-labelledby="cert-heading">
        <h3 id="cert-heading">Certificate</h3>
        <p style={{ margin: 0, fontSize: 14 }}>
          None issued. {worker.moduleResults.length > 0
            ? 'This worker has drill results on file but has not reached the pass mark on a certifiable run.'
            : 'This worker has not attempted a module yet.'}
        </p>
      </section>
    )
  }

  const payload = encodePayload(cert)
  const result = integrity?.[cert.certId]
  const flagged = result
    && result.verdict !== VERDICT.VALID
    && result.verdict !== VERDICT.EXPIRED

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(payload)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard access can be refused; the payload is selectable above, so the
      // demo is not blocked either way.
      setCopied(false)
    }
  }

  return (
    <section className="card counterfoil" aria-labelledby="cert-heading">
      <h3 id="cert-heading">Certificate</h3>

      <dl className="pairs" style={{ gridTemplateColumns: 'minmax(0, 1fr)' }}>
        <div>
          <dt>Certificate id</dt>
          <dd>{cert.certId}</dd>
        </div>
        <div>
          <dt>Issued to</dt>
          <dd>{cert.userName}</dd>
        </div>
        <div>
          <dt>Score at issue</dt>
          <dd>{cert.score} / 100</dd>
        </div>
        <div>
          <dt>Issued · expires</dt>
          <dd>
            {formatDate(cert.issuedDate)} · {formatDate(cert.expiryDate)}{' '}
            <span className="muted">({relativeDays(cert.expiryDate, now)})</span>
          </dd>
        </div>
        <div>
          <dt>Modules at issue</dt>
          {/* The record stores module ids; a reader wants the module names. */}
          <dd className="is-prose">
            {cert.modulesCompleted
              .map((id) => modules.find((module) => module.id === id)?.name ?? id)
              .join(' · ')}
          </dd>
        </div>
        <div>
          <dt>Signature hash</dt>
          <dd>{cert.signatureHash}</dd>
        </div>
      </dl>

      {flagged ? (
        <p className="advisory" style={{ borderLeftColor: 'var(--lapse)', background: 'var(--lapse-wash)' }}>
          <strong>Fails its own signature check — {result.verdict.replace(/_/g, ' ').toLowerCase()}</strong>
          {VERDICT_COPY[result.verdict]}
        </p>
      ) : null}

      <div className="qr-block">
        {/*
          TODO: render the real QR. `npm i qrcode`, then
          `QRCode.toDataURL(payload, { margin: 1, width: 216 })` in an effect and
          swap this div for the <img>. The payload below is byte-identical to what
          the app's ZXing encoder puts in the code, so the swap changes nothing else.
        */}
        <div className="qr-placeholder" role="img" aria-label="QR code placeholder">
          <span>QR<br />placeholder</span>
        </div>
        <p className="qr-note">
          The app renders this payload as a scannable QR code. The dashboard ships
          without a QR encoder, so the exact payload is printed below — paste it into
          Verify to run the same check a phone camera would.
        </p>
      </div>

      <p className="payload">{payload}</p>

      <div className="btn-row">
        <button type="button" className="btn ghost" onClick={copy}>
          {copied ? 'Copied' : 'Copy payload'}
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => go('verify', { prefill: { certId: payload } })}
        >
          Verify this certificate
        </button>
      </div>
    </section>
  )
}
