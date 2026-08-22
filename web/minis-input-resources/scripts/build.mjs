import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as esbuild from 'esbuild'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const packageRoot = resolve(scriptDir, '..')
const repositoryRoot = resolve(packageRoot, '../..')
const assetsRoot = resolve(repositoryRoot, 'src/android/app/src/main/assets/minis')
const manifest = JSON.parse(await readFile(resolve(packageRoot, 'client-manifest.json'), 'utf8'))
const targetDir = resolve(assetsRoot, 'plugins', ...manifest.id.split('/'))
const target = resolve(targetDir, 'client.js')

function shortHash(value) {
  return createHash('sha256').update(value).digest('hex').slice(0, 12)
}

const result = await esbuild.build({
  entryPoints: [resolve(packageRoot, 'src/client/index.ts')],
  bundle: true,
  write: false,
  format: 'cjs',
  platform: 'browser',
  target: ['chrome100'],
  minify: false,
  legalComments: 'inline',
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  external: [
    '@deepseek-ai/cordis',
    '@deepseek-ai/dsh-client-runtime/client',
  ],
})
const built = result.outputFiles[0]
if (built === undefined) throw new Error('esbuild produced no JavaScript output')
const closure = `window.__ModuleLoader__.load({\n  id: ${JSON.stringify(manifest.id)},\n  factory: (require) => {\n    var module = { exports: {} };\n    var exports = module.exports;\n${built.text.split('\n').map(line => `    ${line}`).join('\n')}\n    return module.exports;\n  }\n});\n`
await mkdir(targetDir, { recursive: true })
await writeFile(target, closure)

// Register the plugin in the boot graph (idempotent: replace or insert after
// the orderAfter entry, mirroring web/minis-client-plugin/scripts/build.mjs).
const indexPath = resolve(assetsRoot, 'index.html')
let html = await readFile(indexPath, 'utf8')
const marker = 'window.__MINIS_BOOT__ = '
const markerStart = html.indexOf(marker)
if (markerStart < 0) throw new Error('index.html has no __MINIS_BOOT__ graph')
const jsonStart = markerStart + marker.length
const jsonEnd = html.indexOf('</script>', jsonStart)
if (jsonEnd < 0) throw new Error('index.html boot graph script is unterminated')
const graph = JSON.parse(html.slice(jsonStart, jsonEnd).trim())
const rev = shortHash(closure)
const entry = {
  id: manifest.id,
  url: `/plugins/${manifest.id}/client.js?rev=${rev}`,
  rev,
  inject: manifest.inject,
}
graph.entries = graph.entries.filter(row => row.id !== manifest.id)
const after = graph.entries.findIndex(row => row.id === manifest.orderAfter)
graph.entries.splice(after < 0 ? graph.entries.length : after + 1, 0, entry)
graph.rev = shortHash(JSON.stringify(graph.entries))
html = html.slice(0, jsonStart) + JSON.stringify(graph) + html.slice(jsonEnd)
await writeFile(indexPath, html)

console.log(`Built ${manifest.id} -> ${target}`)
console.log(`client rev ${rev}; boot graph rev ${graph.rev}`)
