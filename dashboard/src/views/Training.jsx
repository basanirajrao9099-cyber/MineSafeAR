import React, { useMemo } from 'react'
import { PASS_THRESHOLD, averageScore, bestScores } from '../domain.js'
import { ArrowRight, MODULE_ICON, Phone, WifiOff } from '../icons.jsx'

/**
 * The module catalogue.
 *
 * Each card describes what the drill actually asks a worker to do, not what the
 * module is "about" — a scenario you can picture is the only description worth
 * printing here. The live pass counts underneath come from the register, so the
 * page reports coverage rather than asserting it.
 */
const COPY = {
  fire_explosion_response: {
    tint: 'peach',
    scenario:
      'A fire starts in the room. Read the fire type, then pick the extinguisher '
      + 'that will actually put it out — CO₂, foam or water. Get it wrong and you '
      + 'get the reason why, not just a buzzer. Then find the safe exit among three '
      + 'AR arrows, two of which lead to a dead end.',
    scores: 'Scored on correct and incorrect taps and time taken.',
  },
  gas_leak_protocol: {
    tint: 'mint',
    scenario:
      'Gas is detected at a confined-space entrance, with the hazard-zone boundary '
      + 'drawn on the floor in AR. Select the PPE this scenario needs — the correct '
      + 'subset, not everything on the rack — then confirm a buddy is stationed at '
      + 'the entrance before you go in.',
    scores: 'Scored on PPE accuracy and the buddy-system step.',
  },
  machinery_lockout: {
    tint: 'lilac',
    scenario:
      'Isolate, lock and tag a machine before maintenance, then prove the isolation '
      + 'held before anything is touched.',
    scores: null,
  },
  ground_support_awareness: {
    tint: 'sky',
    scenario:
      'Read the roof. Spot unsupported ground, loose rock, and the signs a support '
      + 'has taken load it was never meant to carry.',
    scores: null,
  },
}

export default function Training({ workers, modules, go, synced }) {
  // Pass counts per module, computed once from the best attempt on each record.
  const stats = useMemo(() => {
    const table = new Map(modules.map((module) => [module.id, { attempted: 0, passed: 0, best: [] }]))
    for (const worker of workers) {
      for (const [moduleId, score] of bestScores(worker.moduleResults)) {
        const entry = table.get(moduleId)
        if (!entry) continue
        entry.attempted += 1
        entry.best.push(score)
        if (score >= PASS_THRESHOLD) entry.passed += 1
      }
    }
    return table
  }, [workers, modules])

  return (
    <>
      <div className="page-head">
        <h1>AR Training</h1>
        {synced}
      </div>

      <p className="hero-lede" style={{ marginBottom: 26 }}>
        Each module is a scenario, not a slide deck. The phone finds the floor, anchors
        the hazard and the equipment where the worker is actually standing, and scores
        the decisions they make. Nothing needs a signal.
      </p>

      <div className="training-grid">
        {modules.map((module) => (
          <ModuleCard
            key={module.id}
            module={module}
            stats={stats.get(module.id)}
            go={go}
          />
        ))}
      </div>

      <div className="callout">
        <Phone />
        <div>
          <strong>What it needs to run</strong>
          An ARCore-capable Android phone on Android 10 or newer, with camera access and
          enough light to find a floor plane. Every model, sound file and string is bundled
          in the APK — no download, no account, no signal.
        </div>
      </div>

      {/*
        The catalogue and the APK are not the same thing, and a training page that
        implies otherwise is the one thing here that could actually mislead someone.
      */}
      <div className="callout">
        <WifiOff />
        <div>
          <strong>Build status, stated plainly</strong>
          Fire &amp; explosion response is the module built end to end. The gas-leak
          protocol is specified and carried in this catalogue, but its AR scene is not
          in the app yet; the two planned modules have no scene at all. The register
          this dashboard reads is demonstration data, so it shows results for both
          modules marked live.
        </div>
      </div>
    </>
  )
}

function ModuleCard({ module, stats, go }) {
  const copy = COPY[module.id] ?? { tint: 'lilac', scenario: '', scores: null }
  const Icon = MODULE_ICON[module.id]
  const live = module.status === 'live'
  const attempted = stats?.attempted ?? 0
  const average = attempted > 0 ? averageScore(stats.best) : null

  return (
    <article
      className={live ? 'module-card' : 'module-card is-planned'}
      data-tint={copy.tint}
    >
      <div className="module-card-head">
        {Icon ? (
          <span className="module-card-icon"><Icon /></span>
        ) : null}
        <h3>{module.name}</h3>
      </div>

      <p>{copy.scenario}</p>
      {copy.scores ? <p className="hint">{copy.scores}</p> : null}

      <div className="module-card-foot">
        {live ? (
          <span className="badge is-live"><i />In the catalogue</span>
        ) : (
          <span className="badge is-soon">Planned</span>
        )}
        <span className={module.required ? 'badge is-required' : 'badge'}>
          {module.required ? 'Required' : 'Optional'}
        </span>

        {live && attempted > 0 ? (
          <>
            <span className="badge">
              {stats.passed} of {attempted} passed · avg {average}
            </span>
            {/*
              Deep-links into the register pre-filtered to the people who have not
              passed this module — the reason anyone reads a pass rate.
            */}
            <button
              type="button"
              className="feature-go"
              style={{ marginLeft: 'auto', paddingTop: 0, border: 0, background: 'none', cursor: 'pointer' }}
              onClick={() => go('workers', { filters: { module: `fail:${module.id}` } })}
            >
              Who hasn't passed <ArrowRight />
            </button>
          </>
        ) : null}
      </div>
    </article>
  )
}
