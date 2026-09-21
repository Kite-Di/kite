import { defineConfig } from 'vitepress'

// The site is served from the apex of kitedi.com (see public/CNAME), so no base path.
export default defineConfig({
  title: 'Kite',
  description: 'Dependency injection for Android where the graph is inferred, not declared.',
  lang: 'en-US',
  cleanUrls: true,
  lastUpdated: true,
  // Notes for whoever produces the screenshots — not a page.
  srcExclude: ['IMAGES.md'],

  head: [
    ['link', { rel: 'icon', href: '/img/favicon.png' }],
    ['meta', { property: 'og:title', content: 'Kite — the graph is inferred, not declared' }],
    ['meta', { property: 'og:image', content: 'https://kitedi.com/img/social.png' }],
  ],

  themeConfig: {
    logo: '/img/mascot.png',

    nav: [
      { text: 'Guide', link: '/guide/getting-started', activeMatch: '/guide/' },
      { text: 'Tutorial', link: '/guide/tutorial' },
      { text: 'Board', link: '/guide/board' },
      { text: '0.1.1', items: [{ text: 'Changelog', link: 'https://github.com/Kite-Di/kite/releases' }] },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Start here',
          items: [
            { text: 'Getting started', link: '/guide/getting-started' },
            { text: 'Tutorial', link: '/guide/tutorial' },
            { text: 'Coming from Hilt or Dagger', link: '/guide/migration' },
          ],
        },
        {
          text: 'Core concepts',
          items: [
            { text: 'How the graph is inferred', link: '/guide/inference' },
            { text: 'Decisions — GraphRules.kt', link: '/guide/rules' },
            { text: 'Injecting', link: '/guide/injecting' },
            { text: 'Lifetimes', link: '/guide/lifetimes' },
            { text: 'ViewModels & Compose', link: '/guide/viewmodels' },
            { text: 'Patterns', link: '/guide/patterns' },
          ],
        },
        {
          text: 'Going further',
          items: [
            { text: 'Multi-module apps', link: '/guide/multi-module' },
            { text: 'The board', link: '/guide/board' },
            { text: 'Testing', link: '/guide/testing' },
            { text: 'Build errors', link: '/guide/errors' },
            { text: 'Limitations', link: '/guide/limitations' },
          ],
        },
      ],
    },

    socialLinks: [{ icon: 'github', link: 'https://github.com/Kite-Di/kite' }],

    search: { provider: 'local' },

    editLink: {
      pattern: 'https://github.com/Kite-Di/kite/edit/main/website/:path',
      text: 'Edit this page on GitHub',
    },

    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright © 2026 Kite',
    },
  },
})
