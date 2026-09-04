import { defineConfig } from 'vite'

/**
 * There is deliberately no `@vitejs/plugin-react` here.
 *
 * Vite's default `.jsx` handling is esbuild's classic transform, which is also
 * what `tools/serve-nobuild.mjs` does with sucrase — so the sources that run
 * under the no-install fallback are the same sources, through the same
 * transform, as the ones that run under Vite. Every `.jsx` file imports React
 * explicitly for that reason.
 *
 * The cost is React Fast Refresh: editing a component reloads the page instead of
 * preserving its state. If you want it back:
 *
 *     npm i -D @vitejs/plugin-react
 *     plugins: [react()]
 *
 * which switches to the automatic JSX runtime and makes the explicit React
 * imports redundant (harmless, but you can drop them).
 */
export default defineConfig({
  server: {
    port: 5180,
    open: false,
  },
})
