import React from 'react'
import { STATUS_LABEL, STATUS_ORDER, PASS_THRESHOLD } from './domain.js'

/**
 * The compliance band: the whole workforce as one bar, segmented by standing.
 *
 * Four stat cards would report the same numbers and answer a different question.
 * The bar answers "how much of this workforce is covered" before you read a digit,
 * because the proportions are the message. Ticks every 10% (drawn in CSS) let you
 * read a rough share off it without a legend.
 *
 * Used twice at two scales — full size on the Overview, `mini` per site row. Same
 * grammar, so the small one needs no explaining once you have seen the large one.
 */
export function ComplianceBand({ counts, total, mini = false, label }) {
  const segments = STATUS_ORDER
    .map((status) => ({ status, count: counts[status] ?? 0 }))
    .filter((segment) => segment.count > 0)

  if (total === 0) {
    return <div className={mini ? 'band is-mini' : 'band'} role="presentation" />
  }

  return (
    <div
      className={mini ? 'band is-mini' : 'band'}
      role="img"
      aria-label={
        label ??
        segments.map((s) => `${s.count} ${STATUS_LABEL[s.status].toLowerCase()}`).join(', ')
      }
    >
      {segments.map(({ status, count }) => {
        const share = (count / total) * 100
        return (
          <div
            key={status}
            className="band-seg"
            data-status={status}
            style={{ flexGrow: count, flexBasis: 0 }}
          >
            {/* Below about a tenth of the bar the label would be clipped to a
                letter and a half, which reads as a rendering fault. The legend
                underneath carries every count regardless. The label is wrapped
                so its inset lives on the span — padding on the segment itself
                would distort the segment's width. */}
            {!mini && share >= 11 ? (
              <span className="band-label">{count} {STATUS_LABEL[status]}</span>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

/** The counts under the band, in full — including the segments too small to label. */
export function BandLegend({ counts }) {
  return (
    <ul className="band-legend">
      {STATUS_ORDER.filter((status) => (counts[status] ?? 0) > 0).map((status) => (
        <li key={status}>
          <span className="swatch" data-status={status} aria-hidden="true" />
          <b>{counts[status]}</b>
          <span className="muted">{STATUS_LABEL[status].toLowerCase()}</span>
        </li>
      ))}
    </ul>
  )
}

/**
 * A stripe and a word. Not a pill — a column of twenty-four filled pills is a lot
 * of colour for something you only need to be able to scan.
 */
export function StatusTag({ status }) {
  return (
    <span className="status" data-status={status}>
      {STATUS_LABEL[status]}
    </span>
  )
}

/**
 * A score bar with the pass mark ticked on it.
 *
 * The tick is the whole point. "68" needs arithmetic; "68, and here is where 80
 * is" needs none, which matters when the reader is scanning fourteen rows for the
 * one that fell short.
 */
export function ScoreBar({ score, threshold = PASS_THRESHOLD }) {
  const failed = score < threshold
  return (
    <div className={failed ? 'score-bar is-fail' : 'score-bar'}>
      <i style={{ width: `${Math.max(0, Math.min(100, score))}%` }} />
      <b style={{ left: `${threshold}%` }} title={`Pass mark ${threshold}%`} />
    </div>
  )
}

/** A label bound to its control by nesting, so no id juggling and no orphan labels. */
export function Field({ label, hint, grow = false, className = '', children }) {
  return (
    <label className={`field${grow ? ' grow' : ''}${className ? ` ${className}` : ''}`}>
      <span>{label}</span>
      {children}
      {hint ? <p className="hint">{hint}</p> : null}
    </label>
  )
}

/** Says what happened and offers the way out of it. */
export function Empty({ title, children, action }) {
  return (
    <div className="card empty">
      <h2>{title}</h2>
      {children ? <p>{children}</p> : null}
      {action}
    </div>
  )
}

/**
 * Two hashes, with the first differing character onward marked in the submitted one.
 *
 * Sixty-four hex characters are otherwise indistinguishable at a glance, and "the
 * hashes differ" is much less useful than being able to see where.
 */
export function HashCompare({ submitted, expected }) {
  const divergeAt = firstDifference(submitted, expected)
  return (
    <div className="hash-compare">
      <div>
        <span className="label">Submitted</span>
        <p className="hash">
          {submitted == null ? (
            <span className="muted">— not supplied —</span>
          ) : divergeAt < 0 ? (
            submitted
          ) : (
            <>
              {submitted.slice(0, divergeAt)}
              <mark>{submitted.slice(divergeAt)}</mark>
            </>
          )}
        </p>
      </div>
      <div>
        <span className="label">Recomputed from the signed fields</span>
        <p className="hash">{expected ?? <span className="muted">— nothing to compute from —</span>}</p>
      </div>
    </div>
  )
}

function firstDifference(a, b) {
  if (!a || !b) return -1
  const limit = Math.min(a.length, b.length)
  for (let i = 0; i < limit; i += 1) {
    if (a[i] !== b[i]) return i
  }
  return a.length === b.length ? -1 : limit
}
