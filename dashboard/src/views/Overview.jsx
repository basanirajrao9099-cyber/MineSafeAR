import React, { useMemo } from 'react'
import {
  EXPIRING_SOON_DAYS,
  STATUS,
  STATUS_LABEL,
  bySite,
  summarise,
} from '../domain.js'
import { BandLegend, ComplianceBand } from '../components.jsx'

/**
 * One question, asked by every safety officer and every inspector: how much of
 * this workforce is covered right now. The band answers it; everything below is
 * detail in descending order of how often it gets asked.
 */
export default function Overview({ workers, sites, modules, integrity, now, go, synced }) {
  const summary = useMemo(
    () => summarise(workers, integrity, modules, now),
    [workers, integrity, modules, now],
  )
  const siteRows = useMemo(
    () => bySite(workers, sites, integrity, now),
    [workers, sites, integrity, now],
  )

  const flagged = summary.integrity.failed

  return (
    <>
      <div className="page-head">
        <h1>Overview</h1>
        {synced}
      </div>

      <section className="card band-card" aria-labelledby="band-heading">
        <div className="band-top">
          <div className="band-figure">
            {summary.certifiedPercent}%
            <small id="band-heading">Hold current certification</small>
          </div>
          <div className="band-wrap">
            <ComplianceBand
              counts={summary.counts}
              total={summary.total}
              label={`Standing of all ${summary.total} workers on the register`}
            />
            <BandLegend counts={summary.counts} />
          </div>
        </div>
      </section>

      <dl className="figures">
        <div className="figure">
          <dt>Workers trained</dt>
          <dd>
            {summary.trained}
            <small>of {summary.total}</small>
          </dd>
        </div>
        <div className="figure">
          <dt>Average score</dt>
          <dd>
            {summary.averageScore}
            <small>/ 100</small>
          </dd>
        </div>
        <div className="figure">
          <dt>Expiring in {EXPIRING_SOON_DAYS} days</dt>
          <dd>{summary.expiringSoon}</dd>
        </div>
        <div className="figure">
          <dt>Lapsed</dt>
          <dd>{summary.counts[STATUS.EXPIRED]}</dd>
        </div>
        <div className="figure">
          <dt>Sites</dt>
          <dd>{summary.siteCount}</dd>
        </div>
        <div className="figure">
          <dt>Live modules</dt>
          <dd>
            {summary.moduleCount}
            <small>of {modules.length}</small>
          </dd>
        </div>
      </dl>

      {/*
        The line a phone cannot produce. It holds one certificate; the register
        holds all of them, so it can re-derive every signature at load and say
        whether the set hangs together.
      */}
      <p className={flagged > 0 ? 'integrity is-bad' : 'integrity'}>
        <strong>Record integrity</strong>
        {flagged === 0 ? (
          <span>
            All {summary.integrity.checked} synced certificates re-signed cleanly. No
            record has been altered since it was issued.
          </span>
        ) : (
          <span>
            {summary.integrity.checked} certificates checked, <b>{flagged} failed</b>. A
            failed record is shown as {STATUS_LABEL[STATUS.FLAGGED].toLowerCase()} and is
            not counted as certification — open the worker, or paste the certificate into{' '}
            <button type="button" className="link" onClick={() => go('verify')}>
              Verify
            </button>{' '}
            to see which field moved.
          </span>
        )}
      </p>

      <h2 className="section-head" id="by-site">By site</h2>
      <div className="table-wrap">
        <table aria-labelledby="by-site">
          <thead>
            <tr>
              <th scope="col" className="plain">Site</th>
              <th scope="col" className="plain">Standing</th>
              <th scope="col" className="plain num">Workers</th>
              <th scope="col" className="plain num">Current</th>
            </tr>
          </thead>
          <tbody>
            {siteRows.map(({ site, counts, workers: headcount, percent }) => (
              <tr
                key={site.id}
                className="row-link"
                onClick={() => go('workers', { filters: { site: site.id } })}
              >
                {/*
                  The whole row is clickable for the mouse, but the keyboard target
                  is a real <button> in the first cell — so the row keeps its table
                  semantics instead of being relabelled as a link, and Enter works
                  because the button's click bubbles to the handler above.
                */}
                <td className="name-cell">
                  <button type="button" className="cell-btn">{site.name}</button>
                  <small>{site.district}, {site.state}</small>
                </td>
                <td style={{ minWidth: 180 }}>
                  <ComplianceBand
                    counts={counts}
                    total={headcount}
                    mini
                    label={`${site.shortName}: ${percent}% hold current certification`}
                  />
                </td>
                <td className="num">{headcount}</td>
                <td className="num">{percent}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint" style={{ marginTop: 8 }}>
        Worst-covered site first. Select a row to open its workers.
      </p>
    </>
  )
}
