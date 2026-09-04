import React, { useMemo, useState } from 'react'
import {
  PASS_THRESHOLD,
  STATUS,
  STATUS_LABEL,
  STATUS_ORDER,
  averageScore,
  bestScores,
  formatDate,
  relativeDays,
  statusOf,
} from '../domain.js'
import { Empty, Field, StatusTag } from '../components.jsx'

/**
 * Sorted most-urgent-first by default, which is not the same as the band's display
 * order. A tampered record outranks a lapsed one, and someone who has never started
 * is a bigger problem than someone part-way through.
 */
const ATTENTION = [
  STATUS.FLAGGED,
  STATUS.EXPIRED,
  STATUS.EXPIRING,
  STATUS.NOT_STARTED,
  STATUS.IN_PROGRESS,
  STATUS.CERTIFIED,
]

const COLUMNS = [
  { key: 'name', label: 'Worker' },
  { key: 'site', label: 'Site' },
  { key: 'status', label: 'Standing' },
  { key: 'score', label: 'Best avg', num: true },
  { key: 'modules', label: 'Passed', num: true },
  { key: 'expiry', label: 'Certificate expires' },
]

const BLANK = { search: '', site: 'all', status: 'all', module: 'all' }

export default function Workers({ workers, sites, modules, integrity, now, index, go, initialFilters }) {
  const [filters, setFilters] = useState({ ...BLANK, ...initialFilters })
  const [sort, setSort] = useState({ key: 'status', dir: 'asc' })

  const liveModules = modules.filter((module) => module.status === 'live')

  // One derived row per worker, computed once. Everything the table sorts or
  // filters on lives here, so the comparators stay one-liners.
  const rows = useMemo(() => workers.map((worker) => {
    const best = bestScores(worker.moduleResults)
    const site = index.siteById.get(worker.siteId)
    return {
      worker,
      site,
      best,
      status: statusOf(worker, integrity, now),
      score: worker.moduleResults.length > 0 ? averageScore([...best.values()]) : null,
      passed: liveModules.filter((module) => (best.get(module.id) ?? 0) >= PASS_THRESHOLD).length,
      expiry: worker.certificate?.expiryDate ?? null,
    }
  }), [workers, integrity, now, index, liveModules])

  const filtered = useMemo(() => {
    const needle = filters.search.trim().toLowerCase()
    return rows.filter((row) => {
      if (filters.site !== 'all' && row.worker.siteId !== filters.site) return false
      if (filters.status !== 'all' && row.status !== filters.status) return false

      if (filters.module !== 'all') {
        const [mode, moduleId] = filters.module.split(':')
        const passed = (row.best.get(moduleId) ?? 0) >= PASS_THRESHOLD
        if (mode === 'pass' ? !passed : passed) return false
      }

      if (needle) {
        // Ids are searchable too: an inspector reading off a certificate has an
        // id in hand, not a name.
        const haystack = [
          row.worker.fullName,
          row.worker.employeeCode,
          row.worker.jobRole,
          row.site?.name,
          row.worker.workerId,
          row.worker.certificate?.certId,
        ].join(' ').toLowerCase()
        if (!haystack.includes(needle)) return false
      }

      return true
    })
  }, [rows, filters])

  const sorted = useMemo(() => {
    const factor = sort.dir === 'asc' ? 1 : -1
    const value = (row) => {
      switch (sort.key) {
        case 'site': return row.site?.name ?? ''
        case 'status': return ATTENTION.indexOf(row.status)
        case 'score': return row.score
        case 'modules': return row.passed
        case 'expiry': return row.expiry
        default: return row.worker.fullName
      }
    }
    return [...filtered].sort((a, b) => {
      const left = value(a)
      const right = value(b)
      // Workers with no certificate and no score sort last whichever way the
      // column is pointed — an absent value is not a low one.
      if (left == null && right == null) return a.worker.fullName.localeCompare(b.worker.fullName)
      if (left == null) return 1
      if (right == null) return -1
      if (typeof left === 'string') return left.localeCompare(right) * factor
      if (left === right) return a.worker.fullName.localeCompare(b.worker.fullName)
      return (left - right) * factor
    })
  }, [filtered, sort])

  const toggleSort = (key) => setSort((current) => (
    current.key === key
      ? { key, dir: current.dir === 'asc' ? 'desc' : 'asc' }
      : { key, dir: key === 'name' || key === 'site' || key === 'status' ? 'asc' : 'desc' }
  ))

  const set = (patch) => setFilters((current) => ({ ...current, ...patch }))
  const dirty = JSON.stringify(filters) !== JSON.stringify(BLANK)

  return (
    <>
      <div className="page-head">
        <h1>Workers</h1>
        <span className="mono muted">{workers.length} on the register</span>
      </div>

      <div className="filters">
        <Field label="Search" grow>
          <input
            type="search"
            value={filters.search}
            placeholder="Name, employee code, role or id"
            onChange={(event) => set({ search: event.target.value })}
          />
        </Field>

        <Field label="Site">
          <select value={filters.site} onChange={(event) => set({ site: event.target.value })}>
            <option value="all">All sites</option>
            {sites.map((site) => (
              <option key={site.id} value={site.id}>{site.shortName}</option>
            ))}
          </select>
        </Field>

        <Field label="Standing">
          <select value={filters.status} onChange={(event) => set({ status: event.target.value })}>
            <option value="all">All standings</option>
            {STATUS_ORDER.map((status) => (
              <option key={status} value={status}>{STATUS_LABEL[status]}</option>
            ))}
          </select>
        </Field>

        <Field label="Module">
          <select value={filters.module} onChange={(event) => set({ module: event.target.value })}>
            <option value="all">Any module</option>
            {/*
              The pass/fail sense is spelled into each label rather than left to an
              <optgroup>. A closed select shows the option and not its group, so
              "Fire & explosion response" alone would not say which half it came from.
            */}
            {liveModules.map((module) => (
              <option key={`pass:${module.id}`} value={`pass:${module.id}`}>
                Passed · {module.name}
              </option>
            ))}
            {liveModules.map((module) => (
              <option key={`fail:${module.id}`} value={`fail:${module.id}`}>
                Not passed · {module.name}
              </option>
            ))}
          </select>
        </Field>

        {dirty ? (
          <button type="button" className="btn quiet" onClick={() => setFilters({ ...BLANK })}>
            Clear
          </button>
        ) : null}

        <span className="result-count">
          {sorted.length === workers.length
            ? `${sorted.length} shown`
            : `${sorted.length} of ${workers.length} shown`}
        </span>
      </div>

      {sorted.length === 0 ? (
        <Empty
          title="No workers match these filters"
          action={
            <button type="button" className="btn" onClick={() => setFilters({ ...BLANK })}>
              Clear filters
            </button>
          }
        >
          Nothing on the register fits that combination. Clear the filters to see all{' '}
          {workers.length} records.
        </Empty>
      ) : (
        <div className="table-wrap">
          <table className="stacked">
            <thead>
              <tr>
                {COLUMNS.map((column) => {
                  const active = sort.key === column.key
                  return (
                    <th
                      key={column.key}
                      scope="col"
                      className={column.num ? 'num' : undefined}
                      aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                    >
                      <button type="button" className="sort" onClick={() => toggleSort(column.key)}>
                        {column.label}
                        <span className="sort-caret" aria-hidden="true">
                          {active ? (sort.dir === 'asc' ? '▲' : '▼') : '▽'}
                        </span>
                      </button>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody>
              {sorted.map((row) => (
                <tr
                  key={row.worker.workerId}
                  className="row-link"
                  onClick={() => go('worker', { workerId: row.worker.workerId })}
                >
                  <td className="name-cell" data-cell="name">
                    <button type="button" className="cell-btn">{row.worker.fullName}</button>
                    <small>{row.worker.employeeCode} · {row.worker.jobRole}</small>
                  </td>
                  <td data-cell="site" data-head="Site">{row.site?.shortName ?? '—'}</td>
                  <td data-cell="status">
                    <StatusTag status={row.status} />
                  </td>
                  <td className="num" data-cell="score" data-head="Best avg">
                    {row.score == null ? <span className="muted">—</span> : row.score}
                  </td>
                  <td className="num" data-cell="modules" data-head="Passed">
                    {row.passed} / {liveModules.length}
                  </td>
                  <td data-cell="expiry" data-head="Expires">
                    {row.expiry == null ? (
                      <span className="muted">No certificate</span>
                    ) : (
                      <span className="mono">
                        {formatDate(row.expiry)}
                        {row.status === STATUS.EXPIRING || row.status === STATUS.EXPIRED ? (
                          <em style={{ fontStyle: 'normal' }} className="muted">
                            {' '}({relativeDays(row.expiry, now)})
                          </em>
                        ) : null}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
