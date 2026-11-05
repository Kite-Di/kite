// VitePress fails the build on a link to a missing page, not on a link to a missing
// heading. This checks the rest: every `/guide/page#anchor` and `#anchor` link in the
// sources has an element with that id in the built site. Run after `vitepress build`.
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('..', import.meta.url).pathname
const dist = join(root, '.vitepress/dist')
const sources = ['index.md', ...readdirSync(join(root, 'guide')).filter((f) => f.endsWith('.md')).map((f) => `guide/${f}`)]
const link = /\]\((\/guide\/[\w-]+)?#([^)\s]+)\)/g

const missing = []
for (const source of sources) {
  for (const [, page, anchor] of readFileSync(join(root, source), 'utf8').matchAll(link)) {
    const target = page ?? '/' + source.replace(/\.md$/, '')
    const html = readFileSync(join(dist, `${target}.html`), 'utf8')
    if (!html.includes(`id="${anchor}"`)) missing.push(`${source}: ${target}#${anchor}`)
  }
}

if (missing.length) {
  console.error(`Links to headings that do not exist:\n  ${missing.join('\n  ')}`)
  console.error('A heading with punctuation in it needs an explicit {#id}.')
  process.exit(1)
}
console.log(`Every heading link resolves (${sources.length} pages).`)
