import { createHash } from 'node:crypto'
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as esbuild from 'esbuild'
import { transform } from 'lightningcss'

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

const cssModules = {
  name: 'openminis-css-modules',
  setup(build) {
    build.onLoad({ filter: /\.module\.css$/ }, async args => {
      const source = await readFile(args.path)
      const result = transform({
        filename: args.path,
        code: source,
        minify: true,
        cssModules: { pattern: 'om_[local]_[hash]' },
      })
      const classes = Object.fromEntries(Object.entries(result.exports ?? {}).map(([local, exported]) => [
        local,
        typeof exported === 'string' ? exported : exported.name,
      ]))
      const css = result.code.toString('utf8')
      const tagId = `${manifest.id}/${basename(args.path)}`
      return {
        loader: 'js',
        contents: `
          const css = ${JSON.stringify(css)};
          const tagId = ${JSON.stringify(tagId)};
          if (typeof document !== 'undefined' && document.querySelector('style[data-plugin-css=' + JSON.stringify(tagId) + ']') === null) {
            const tag = document.createElement('style');
            tag.dataset.plugin = ${JSON.stringify(manifest.id)};
            tag.dataset.pluginCss = tagId;
            tag.textContent = css;
            document.head.appendChild(tag);
          }
          export default ${JSON.stringify(classes)};
        `,
        watchFiles: [args.path],
      }
    })
  },
}

const result = await esbuild.build({
  entryPoints: [resolve(packageRoot, 'src/client/index.ts')],
  bundle: true,
  write: false,
  format: 'cjs',
  platform: 'browser',
  target: ['chrome100'],
  jsx: 'automatic',
  minify: false,
  legalComments: 'inline',
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  external: [
    'react',
    'react/jsx-runtime',
    'react-dom',
    'react-dom/client',
    '@deepseek-ai/cordis',
    '@deepseek-ai/dsh-client-runtime/client',
    '@deepseek-ai/dsh-client-ui-slots',
    '@deepseek-ai/dsh-client-ui-primitives',
  ],
  plugins: [cssModules],
})
const built = result.outputFiles[0]
if (built === undefined) throw new Error('esbuild produced no JavaScript output')
const closure = `window.__ModuleLoader__.load({\n  id: ${JSON.stringify(manifest.id)},\n  factory: (require) => {\n    var module = { exports: {} };\n    var exports = module.exports;\n${built.text.split('\n').map(line => `    ${line}`).join('\n')}\n    return module.exports;\n  }\n});\n`
await mkdir(targetDir, { recursive: true })
await writeFile(target, closure)

const indexPath = resolve(assetsRoot, 'index.html')
let html = await readFile(indexPath, 'utf8')
const marker = 'window.__MINIS_BOOT__ = '
const markerStart = html.indexOf(marker)
if (markerStart < 0) throw new Error('index.html has no __MINIS_BOOT__ graph')
const jsonStart = markerStart + marker.length
const jsonEnd = html.indexOf('</script>', jsonStart)
if (jsonEnd < 0) throw new Error('index.html boot graph script is unterminated')
const graph = JSON.parse(html.slice(jsonStart, jsonEnd).trim())
const upstreamGeneral = resolve(assetsRoot, 'plugins/@deepseek-ai/dsh-client-ui-settings-general/client.js')
const generalText = await readFile(upstreamGeneral, 'utf8')
if (generalText.includes('data-minis-control-host') || generalText.includes('MinisControlSection')) {
  throw new Error('ui-settings-general still contains the OpenMinis patch; restore the pinned upstream rc.8 artifact first')
}
const generalRev = shortHash(generalText)
const generalRow = graph.entries.find(row => row.id === '@deepseek-ai/dsh-client-ui-settings-general')
if (generalRow !== undefined) {
  generalRow.rev = generalRev
  generalRow.url = `/plugins/@deepseek-ai/dsh-client-ui-settings-general/client.js?rev=${generalRev}`
}
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
html = html
  .replace(/^\s*<link rel="stylesheet" href="\/minis-control\.css[^\n]*\n?/gm, '')
  .replace(/^\s*<script defer src="\/minis-control\.js[^\n]*\n?/gm, '')
await writeFile(indexPath, html)

await Promise.all([
  rm(resolve(assetsRoot, 'minis-control.js'), { force: true }),
  rm(resolve(assetsRoot, 'minis-control.css'), { force: true }),
])

console.log(`Built ${manifest.id} -> ${target}`)
console.log(`client rev ${rev}; boot graph rev ${graph.rev}`)
