/**
 * A dev server for machines that cannot reach npm.
 *
 * `npm run dev` (Vite) is the normal path and the one to use. This exists because
 * the sandbox this dashboard was built in has `registry.npmjs.org` blocked, so Vite
 * could not be installed — and a UI that has never been run is a UI that does not
 * work. It needs only three packages that were already in the local npm cache:
 * react, react-dom and sucrase.
 *
 * What it does, in order of how much it matters:
 *
 *   1. Transpiles `.jsx` on request with sucrase, classic transform. That is the
 *      same transform esbuild applies to `.jsx` under a plugin-free Vite config,
 *      which is why `vite.config.js` has no `@vitejs/plugin-react` — the sources
 *      that run here are the sources that run there, untouched.
 *   2. Serves React from its UMD build and injects an import map so that
 *      `import React from 'react'` resolves to a tiny ES shim re-exporting the
 *      globals. No bundler, no node_modules resolution in the browser.
 *   3. Serves `public/` at the root, so `/mock/records.json` works as it does
 *      under Vite.
 *
 * What it deliberately does not do: hot reload, source maps, minification, or
 * anything resembling production. Save a file and refresh.
 *
 *     node tools/serve-nobuild.mjs [port]
 */

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { transform } from 'sucrase'

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)))
const PORT = Number(process.argv[2] ?? 5180)

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.jsx': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
}

/**
 * The bare-specifier shims. React's UMD bundles assign `window.React` and
 * `window.ReactDOM`; these re-export those as ES modules so the application's
 * imports need no rewriting.
 */
const SHIMS = {
  '/@shim/react.js':
    'const React = window.React;\n'
    + 'export default React;\n'
    + 'export const {\n'
    + '  useState, useEffect, useMemo, useRef, useCallback, useReducer, useContext,\n'
    + '  createElement, Fragment, StrictMode, createContext, memo, forwardRef,\n'
    + '} = React;\n',
  '/@shim/react-dom-client.js':
    'const ReactDOM = window.ReactDOM;\n'
    + 'export const { createRoot, hydrateRoot } = ReactDOM;\n'
    + 'export default ReactDOM;\n',
}

const IMPORT_MAP = `<script type="importmap">${JSON.stringify({
  imports: {
    react: '/@shim/react.js',
    'react-dom': '/@shim/react-dom-client.js',
    'react-dom/client': '/@shim/react-dom-client.js',
  },
})}</script>
<script src="/@umd/react.js"></script>
<script src="/@umd/react-dom.js"></script>
`

const UMD = {
  '/@umd/react.js': 'node_modules/react/umd/react.development.js',
  '/@umd/react-dom.js': 'node_modules/react-dom/umd/react-dom.development.js',
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://localhost:${PORT}`)
  const path = decodeURIComponent(url.pathname)

  try {
    if (SHIMS[path]) {
      return send(response, 200, MIME['.js'], SHIMS[path])
    }

    if (UMD[path]) {
      return send(response, 200, MIME['.js'], await readFile(join(ROOT, UMD[path])))
    }

    // index.html, with the import map and UMD tags injected ahead of the entry
    // module so the globals exist before any shim is evaluated.
    if (path === '/' || path === '/index.html') {
      const html = await readFile(join(ROOT, 'index.html'), 'utf8')
      return send(response, 200, MIME['.html'], html.replace('</head>', `${IMPORT_MAP}</head>`))
    }

    const file = safeJoin(ROOT, path)
    if (!file) return send(response, 403, 'text/plain', 'Forbidden')

    if (extname(file) === '.jsx') {
      const source = await readFile(file, 'utf8')
      const { code } = transform(source, {
        transforms: ['jsx'],
        jsxRuntime: 'classic',
        filePath: file,
      })
      return send(response, 200, MIME['.js'], code)
    }

    // src/ first, then public/ — same precedence Vite uses.
    const body = await readFile(file).catch(() => readFile(safeJoin(ROOT, join('/public', path))))
    return send(response, 200, MIME[extname(file)] ?? 'application/octet-stream', body)
  } catch (cause) {
    if (cause?.code === 'ENOENT') return send(response, 404, 'text/plain', `Not found: ${path}`)
    console.error(`500 ${path}`, cause)
    return send(response, 500, 'text/plain', String(cause?.message ?? cause))
  }
})

/**
 * Keeps `..` out of the served path. Trivially important for a server on 0.0.0.0.
 * The argument is always root-absolute, hence the leading-slash guard rather than
 * a plain join — a relative argument would resolve against the wrong place.
 */
function safeJoin(root, path) {
  const target = resolve(root, `.${normalize(path.startsWith('/') ? path : `/${path}`)}`)
  return target.startsWith(root) ? target : null
}

function send(response, status, type, body) {
  response.writeHead(status, {
    'content-type': type,
    'cache-control': 'no-store',
  })
  response.end(body)
}

server.listen(PORT, () => {
  console.log(`MineSafeAR dashboard (no-build) → http://localhost:${PORT}`)
  console.log('Sucrase transform, React from UMD. No hot reload — refresh to pick up edits.')
})
