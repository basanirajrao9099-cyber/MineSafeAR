import React from 'react'

/**
 * Hand-drawn line icons.
 *
 * No icon package is installed (and npm is unreachable here), but emoji would
 * undercut the whole look — they carry someone else's colour palette and render
 * differently on every platform. These are plain SVG on a 24-unit grid with a
 * single stroke weight, so they inherit `currentColor` and sit consistently
 * against the pastel tints.
 */
const stroke = {
  width: 24,
  height: 24,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': 'true',
  focusable: 'false',
}

/** Viewfinder brackets around an isometric cube: the placed-object moment. */
export function ArCube(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M3 8.4V5.6A2.6 2.6 0 0 1 5.6 3h2.8" />
      <path d="M15.6 3h2.8A2.6 2.6 0 0 1 21 5.6v2.8" />
      <path d="M21 15.6v2.8A2.6 2.6 0 0 1 18.4 21h-2.8" />
      <path d="M8.4 21H5.6A2.6 2.6 0 0 1 3 18.4v-2.8" />
      <path d="M12 7.9l3.7 1.9v4.4L12 16.1l-3.7-1.9V9.8L12 7.9Z" />
      <path d="M12 12.1l3.7-2.3M12 12.1L8.3 9.8M12 12.1v4" />
    </svg>
  )
}

export function People(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M15.4 20.5v-1.7a3.5 3.5 0 0 0-3.5-3.5H6.9a3.5 3.5 0 0 0-3.5 3.5v1.7" />
      <circle cx="9.4" cy="7.8" r="3.3" />
      <path d="M20.6 20.5v-1.7a3.5 3.5 0 0 0-2.7-3.4" />
      <path d="M15.7 4.8a3.3 3.3 0 0 1 0 6" />
    </svg>
  )
}

export function ShieldCheck(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M12 3l7.2 2.6v5.3c0 4.3-2.9 8.1-7.2 9.4-4.3-1.3-7.2-5.1-7.2-9.4V5.6L12 3Z" />
      <path d="M8.9 11.9l2.4 2.3 4-4.4" />
    </svg>
  )
}

export function Chart(props) {
  return (
    <svg {...stroke} {...props} strokeWidth={2.1}>
      <path d="M4 20.2h16" strokeWidth={1.6} />
      <path d="M7.7 20.2v-5.4" />
      <path d="M12 20.2V8.4" />
      <path d="M16.3 20.2v-8.2" />
    </svg>
  )
}

export function Globe(props) {
  return (
    <svg {...stroke} {...props}>
      <circle cx="12" cy="12" r="8.7" />
      <path d="M3.3 12h17.4" />
      <path d="M12 3.3c2.2 2.5 3.4 5.5 3.4 8.7s-1.2 6.2-3.4 8.7c-2.2-2.5-3.4-5.5-3.4-8.7S9.8 5.8 12 3.3Z" />
    </svg>
  )
}

export function Flame(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M12 21a6 6 0 0 0 6-6c0-4.5-6-12-6-12S6 10.5 6 15a6 6 0 0 0 6 6Z" />
      <path d="M12 17.6a2.3 2.3 0 0 0 2.3-2.3c0-1.7-2.3-4.7-2.3-4.7s-2.3 3-2.3 4.7A2.3 2.3 0 0 0 12 17.6Z" />
    </svg>
  )
}

/** Drifting air, for the gas-leak module. */
export function Gas(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M3.5 8.8h9.7a3 3 0 1 0-3-3" />
      <path d="M3.5 14h12.8a3 3 0 1 1-3 3" />
      <path d="M3.5 19.2h6.2" />
    </svg>
  )
}

export function Lock(props) {
  return (
    <svg {...stroke} {...props}>
      <rect x="4.6" y="10.4" width="14.8" height="9.8" rx="2.6" />
      <path d="M8.5 10.4V7.7a3.5 3.5 0 0 1 7 0v2.7" />
      <path d="M12 14.3v2.2" />
    </svg>
  )
}

/** An arch on two props: a supported roof. */
export function Arch(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M3.6 20.4v-7.2a8.4 8.4 0 0 1 16.8 0v7.2" />
      <path d="M7.6 20.4v-6.6M16.4 20.4v-6.6" />
    </svg>
  )
}

export function ArrowRight(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M4.8 12h13.4" />
      <path d="M13.2 6.4 18.8 12l-5.6 5.6" />
    </svg>
  )
}

export function WifiOff(props) {
  return (
    <svg {...stroke} {...props}>
      <path d="M3.4 9.1A14.4 14.4 0 0 1 7.7 6.6" />
      <path d="M16.1 6.7a14.4 14.4 0 0 1 4.5 2.4" />
      <path d="M6.9 12.7a9.6 9.6 0 0 1 2.8-1.6" />
      <path d="M14.2 11a9.6 9.6 0 0 1 2.9 1.7" />
      <path d="M9.9 15.9a5.1 5.1 0 0 1 4.2 0" />
      <path d="M12 19.4h.01" />
      <path d="M3 3l18 18" />
    </svg>
  )
}

export function Phone(props) {
  return (
    <svg {...stroke} {...props}>
      <rect x="6.4" y="2.6" width="11.2" height="18.8" rx="2.8" />
      <path d="M10.4 5.6h3.2" />
      <path d="M12 18.3h.01" />
    </svg>
  )
}

export const MODULE_ICON = {
  fire_explosion_response: Flame,
  gas_leak_protocol: Gas,
  machinery_lockout: Lock,
  ground_support_awareness: Arch,
}
