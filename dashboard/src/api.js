/**
 * The mock API.
 *
 * `records.json` is fetched over HTTP rather than imported, so this really is an
 * async call with a loading state and a failure state — the two things a demo
 * built on a bundled import quietly skips, and the two things that break first
 * when a real backend arrives.
 *
 * Pointing this at a real service is a one-line change. The shape it expects is
 * the sync wire format: see `app/src/main/java/com/minesafear/sync/SyncPayloads.kt`.
 */

import { checkIntegrity } from './domain.js'

const ENDPOINT = '/mock/records.json'

/**
 * Loads the register and audits every certificate in it.
 *
 * Integrity is checked here rather than per-view because it is a property of the
 * data, not of a screen, and because `crypto.subtle` is async — doing it once at
 * load keeps every component that renders a status synchronous.
 *
 * `now` is captured once for the whole session so the Overview's counts, the
 * table's statuses and the Verify page's expiry check cannot disagree by a few
 * milliseconds mid-render.
 */
export async function loadRegister() {
  const response = await fetch(ENDPOINT)
  if (!response.ok) {
    throw new Error(`The register could not be loaded (HTTP ${response.status}).`)
  }

  const data = await response.json()
  const now = Date.now()
  const integrity = await checkIntegrity(data.workers, now)

  return { ...data, integrity, now }
}
