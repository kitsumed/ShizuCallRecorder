import { defineConfig, type HeadConfig} from 'vitepress'

const goatcounterUrl = 'https://kitsumed.goatcounter.com/count';
const baseHostname = 'https://kitsumed.github.io'
const basePath = '/ShizuCallRecorder/'
const fullSiteUrl = `${baseHostname}${basePath}` // "https://kitsumed.github.io/ShizuCallRecorder/"

// https://vitepress.dev/reference/site-config
export default defineConfig({
  title: "ShizuCallRecorder",
  lang: "en-ca",
  cleanUrls: true,
  description: "Free, open-source, FOSS and privacy friendly Android call recording app with no ads or subscriptions. Empowers ADB to record phone calls on non-rooted device!",
  base: basePath, // We overwrite the root "/" base for "/repo-name/" due to how github pages work. CASE SENSITIVE
  sitemap: { // Optional, create a sitemap.xml file
    hostname: fullSiteUrl,
    lastmodDateOnly: false,
  },
  head: [

    // Open Graph / Social Media Meta Tags
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'ShizuCallRecorder' }],
    ['meta', { property: 'og:image', content: 'https://raw.githubusercontent.com/kitsumed/ShizuCallRecorder/main/fastlane/metadata/android/en-US/images/featureGraphic.png' }],
    ['meta', { property: 'og:locale', content: 'en_CA' }],

    // Twitter Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:image', content: 'https://raw.githubusercontent.com/kitsumed/ShizuCallRecorder/main/fastlane/metadata/android/en-US/images/featureGraphic.png' }],
    ['meta', { name: 'twitter:creator', content: '@kitsumed' }],

    ['meta', { name: 'robots', content: 'index, follow' }],
    ['link', { rel: 'icon', type: 'image/png', href: `${basePath}favicon-96x96.png` }],
    // GoatCounter Analytics (Privacy Friendly)
      ['script', {
        src: '/ShizuCallRecorder/analytics.js',
        defer: '',
        'data-goatcounter': goatcounterUrl}
      ],
      ['script', {
        src: '/ShizuCallRecorder/analytics-SPA.js',
        defer: ''}
      ],
  ],
  themeConfig: {
    logo: '/favicon-96x96.png',
    // https://vitepress.dev/reference/default-theme-config
    nav: [
      { text: 'Home', link: '/' },
      { text: 'Documentation', link: '/SUPPORT' },
      { text: 'Translate App', link: 'https://hosted.weblate.org/engage/shizucallrecorder/' },
      { text: '❤️ Support The Project', link: 'https://github.com/sponsors/kitsumed' }
    ],

    sidebar: [
      {
        text: 'Documentation',
        link: '/SUPPORT',
        items: [
          { text: 'Introduction', link: '/introduction' },
          { text: 'Installation & Configuration', link: '/configuration' },
          {
            text: 'Troubleshooting & FAQ',
            collapsed: false,
            link: '/troubleshooting',
            items: [
              { text: 'Permissions Granting Issues', link: '/troubleshooting/permissions-granting-issues' },
              { text: 'Background Application Startup Failures', link: '/troubleshooting/background-application-startup-failures' },
              { text: 'Silent Recording or Missing One Side', link: '/troubleshooting/silent-recording-or-missing-one-side' },
              { text: 'Integration Into Native OEM Dialer', link: '/troubleshooting/integration-into-native-oem-dialer' },
              { text: 'How to Hide The Recording Notification', link: '/troubleshooting/how-to-hide-the-recording-notification' },
            ]
          },
          { text: 'Misleading & Unsafe Forks', link: '/untrusted-and-misleading-forks' },
        ]
      }
    ],

    socialLinks: [
      { icon: 'github', link: 'https://github.com/kitsumed/ShizuCallRecorder' }
    ],

    search: {
      provider: 'local',
      options: {
        detailedView: true,
      }
    },

    footer: {
      message: 'Released under the <a href="https://github.com/kitsumed/ShizuCallRecorder/blob/main/LICENSE">GPL-3.0 license</a>',
      copyright: 'Copyright © 2026-present <a href="https://github.com/kitsumed">kitsumed (Med)</a>'
    },

    editLink: {
      pattern: 'https://github.com/kitsumed/ShizuCallRecorder/edit/main/docs/:path',
      text: 'Edit this page on GitHub'
    }
  },
  markdown: {
    lineNumbers: true,
  },
  lastUpdated: true,
  
  // Transform the head to include GoatCounter noscript tag via pixel tracking + per page metadata
  transformHead({ page, pageData, siteData, title }) {
    const metadataHeaders: HeadConfig[] = []
    const base = siteData.base || '/'
    const routePath = page
      .replace(/(^|\/)index\.md$/, '$1')
      .replace(/\.md$/, '')
    const cleanPath = base + routePath

    metadataHeaders.push(['link', { rel: 'canonical', href: `${baseHostname}${cleanPath}` }])

    const rawTitle = pageData.frontmatter?.title || pageData.title || title
    const pageTitle = rawTitle && rawTitle !== siteData.title
      ? `${rawTitle} | ${siteData.title}`
      : siteData.title

    if (pageTitle) {
      metadataHeaders.push(['meta', { property: 'og:title', content: pageTitle }])
      metadataHeaders.push(['meta', { property: 'twitter:title', content: pageTitle }])
    }

    const description = pageData.frontmatter?.description || pageData.description || siteData.description
    if (description) {
      metadataHeaders.push(['meta', { property: 'og:description', content: description }])
      metadataHeaders.push(['meta', { property: 'twitter:description', content: description }])
    }

    const pageUrl = `${baseHostname}${cleanPath}`
    metadataHeaders.push(['meta', { property: 'og:url', content: pageUrl }])

    // GoatCounter noscript analytics
    const params = new URLSearchParams({
      p: cleanPath,
      t: pageTitle
    })

    metadataHeaders.push([
      'noscript',
      {},
      `<img src="${goatcounterUrl}?${params.toString()}" alt="">`]
    )

    return metadataHeaders
  }
})
