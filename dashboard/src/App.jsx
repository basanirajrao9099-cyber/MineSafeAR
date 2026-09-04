import React, { useEffect, useMemo, useState } from 'react'
import { loadRegister } from './api.js'
import { formatDate } from './domain.js'
import { ArCube } from './icons.jsx'
import Home from './views/Home.jsx'
import Training from './views/Training.jsx'
import Overview from './views/Overview.jsx'
import Workers from './views/Workers.jsx'
import WorkerDetail from './views/WorkerDetail.jsx'
import Verify from './views/Verify.jsx'

/**
 * Six screens, so routing is a piece of state.
 *
 * A router would earn its place the moment these needed shareable URLs or a back
 * button. They don't yet — this is a demo surface driven from a top bar — and a
 * dependency that exists to hold one string is a dependency that has to be
 * explained. `route.params` is how the worker detail, the pre-filled Verify page
 * and the pre-filtered Workers table get their argument.
 */
const NAV = [
  { id: 'home', label: 'Home' },
  { id: 'training', label: 'AR Training' },
  { id: 'overview', label: 'Overview' },
  { id: 'workers', label: 'Workers' },
  { id: 'verify', label: 'Verify' },
]

export default function App() {
  const [register, setRegister] = useState(null)
  const [error, setError] = useState(null)
  const [route, setRoute] = useState({ view: 'home', params: {} })

  useEffect(() => {
    let cancelled = false
    loadRegister()
      .then((data) => { if (!cancelled) setRegister(data) })
      .catch((cause) => { if (!cancelled) setError(cause) })
    return () => { cancelled = true }
  }, [])

  const go = (view, params = {}) => {
    setRoute({ view, params })
    window.scrollTo({ top: 0 })
  }

  if (error) {
    return (
      <div className="boot">
        <div>
          <h1>Register unavailable</h1>
          <p>{error.message}</p>
          <p className="mono muted">{'GET /mock/records.json'}</p>
          <button type="button" className="btn" onClick={() => window.location.reload()}>
            Try again
          </button>
        </div>
      </div>
    )
  }

  if (!register) {
    return (
      <div className="boot">
        <div>
          <h1>Loading register</h1>
          <p>Reading synced records and verifying every certificate signature.</p>
        </div>
      </div>
    )
  }

  // The bar highlights Workers while a worker detail is open: the detail is a
  // place within Workers, not a sixth destination.
  const current = route.view === 'worker' ? 'workers' : route.view

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar-inner">
          <button type="button" className="brand" onClick={() => go('home')}>
            <span className="brand-mark" aria-hidden="true"><ArCube /></span>
            {/*
              "AR" stays welded to the name rather than living in the descriptor
              line — the descriptor is hidden on narrow screens, and dropping the
              AR with it left the product reading as "MineSafe".
            */}
            <span className="wordmark">
              MineSafe<span className="ar">AR</span>
              <span className="tag">training register</span>
            </span>
          </button>

          <nav aria-label="Sections">
            {NAV.map((item) => (
              <button
                key={item.id}
                type="button"
                className="nav-link"
                aria-current={current === item.id ? 'page' : undefined}
                onClick={() => go(item.id)}
              >
                {item.label}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <main className={route.view === 'home' ? 'page is-home' : 'page'}>
        <Screen route={route} register={register} go={go} />
      </main>

      <footer className="footer">
        <p className="footer-inner">
          <strong>Demonstration data</strong>
          Records are generated and the sites are fictional. Certificate signatures are
          real SHA-256, computed with a salt that also ships inside the app — so a valid
          signature proves a record is unaltered, not that a worker is authorised.
        </p>
      </footer>
    </div>
  )
}

function Screen({ route, register, go }) {
  const { workers, sites, modules, integrity, now, generatedAtMillis } = register

  // Lookups the views would otherwise rebuild on every keystroke.
  const index = useMemo(() => ({
    siteById: new Map(sites.map((site) => [site.id, site])),
    moduleById: new Map(modules.map((module) => [module.id, module])),
    workerById: new Map(workers.map((worker) => [worker.workerId, worker])),
  }), [workers, sites, modules])

  const synced = (
    <span className="mono muted">
      Register synced {formatDate(generatedAtMillis ?? now)}
    </span>
  )

  const shared = { workers, sites, modules, integrity, now, index, go, synced }

  switch (route.view) {
    case 'training':
      return <Training {...shared} />
    case 'overview':
      return <Overview {...shared} />
    case 'workers':
      return <Workers {...shared} initialFilters={route.params.filters} />
    case 'worker':
      return <WorkerDetail {...shared} workerId={route.params.workerId} />
    case 'verify':
      return <Verify {...shared} prefill={route.params.prefill} />
    default:
      return <Home {...shared} />
  }
}
