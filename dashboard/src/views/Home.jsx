import React, { useMemo } from 'react'
import { EXPIRING_SOON_DAYS, PASS_THRESHOLD, summarise } from '../domain.js'
import { ArCube, ArrowRight, Chart, People, ShieldCheck, WifiOff } from '../icons.jsx'

/**
 * The landing page.
 *
 * One thing is meant to be unmissable — the AR training panel — so it gets the
 * full width, the gradient, the only large icon and the only arrow. Everything
 * else on the page is deliberately quieter: three small feature cards for the
 * admin surfaces, then the register's real figures so the page isn't making
 * claims the data can't back.
 */
export default function Home({ workers, sites, modules, integrity, now, go }) {
  const summary = useMemo(
    () => summarise(workers, integrity, modules, now),
    [workers, integrity, modules, now],
  )

  const liveModules = modules.filter((module) => module.status === 'live').length

  return (
    <>
      <section className="hero">
        <p className="hero-eyebrow">
          <WifiOff />
          Runs with no network, underground
        </p>

        <h1 className="hero-title">
          Safety training you can <em>walk through</em>.
        </h1>

        <p className="hero-lede">
          MineSafeAR puts a live hazard drill in a worker's hands — a fire in the room,
          gas at a confined-space entrance — and scores what they actually do. Pass it
          and the certificate is signed on the phone, on the spot, offline.
        </p>
      </section>

      {/* The one button that has to be seen before anything else on the page. */}
      <button type="button" className="cta-ar" onClick={() => go('training')}>
        <span className="cta-ar-blob" aria-hidden="true" />

        <span className="cta-ar-icon">
          <ArCube />
        </span>

        <span className="cta-ar-text">
          <span className="cta-ar-kicker">Start here</span>
          <span className="cta-ar-title">AR Training</span>
          <span className="cta-ar-sub">
            Step into the scenario. Find the right extinguisher for the fire type,
            then the safe exit — with decoys that lead nowhere.
          </span>
          <span className="cta-ar-meta">
            <span>{liveModules} modules live</span>
            <span><WifiOff /> Fully offline</span>
            <span>Pass mark {PASS_THRESHOLD}</span>
          </span>
        </span>

        <span className="cta-ar-go" aria-hidden="true">
          <ArrowRight />
        </span>
      </button>

      <h2 className="section-head">And for whoever signs off on it</h2>

      <div className="feature-grid">
        <Feature
          tint="sky"
          icon={<People />}
          title="Worker register"
          desc={`All ${summary.total} workers across ${summary.siteCount} sites — searchable, filterable by site, standing and module.`}
          onClick={() => go('workers')}
        />
        <Feature
          tint="mint"
          icon={<ShieldCheck />}
          title="Verify a certificate"
          desc="Paste a certificate and re-derive its signature. Catches an edited score or a stretched expiry date."
          onClick={() => go('verify')}
        />
        <Feature
          tint="lilac"
          icon={<Chart />}
          title="Coverage analytics"
          desc="How much of the workforce is currently covered, which site is worst, and what lapses this month."
          onClick={() => go('overview')}
        />
      </div>

      <h2 className="section-head">The register right now</h2>

      <dl className="stat-strip">
        <div className="stat">
          <dt>Workers</dt>
          <dd>{summary.total}</dd>
        </div>
        <div className="stat">
          <dt>Hold certification</dt>
          <dd>{summary.certifiedPercent}<small>%</small></dd>
        </div>
        <div className="stat">
          <dt>Average score</dt>
          <dd>{summary.averageScore}<small>/ 100</small></dd>
        </div>
        <div className="stat">
          <dt>Expiring in {EXPIRING_SOON_DAYS} days</dt>
          <dd>{summary.expiringSoon}</dd>
        </div>
        <div className="stat">
          <dt>Sites</dt>
          <dd>{sites.length}</dd>
        </div>
      </dl>

      <p className="hint" style={{ marginTop: 12 }}>
        Live figures, re-derived from the synced records on every load —{' '}
        <button type="button" className="link" onClick={() => go('overview')}>
          see the breakdown
        </button>.
      </p>
    </>
  )
}

function Feature({ tint, icon, title, desc, onClick }) {
  return (
    <button type="button" className="feature" data-tint={tint} onClick={onClick}>
      <span className="feature-icon">{icon}</span>
      <span className="feature-title">{title}</span>
      <span className="feature-desc">{desc}</span>
      <span className="feature-go">
        Open <ArrowRight />
      </span>
    </button>
  )
}
