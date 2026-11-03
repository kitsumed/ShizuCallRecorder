import { defineConfig } from 'vitepress'

// https://vitepress.dev/reference/site-config
export default defineConfig({
  title: "ShizuCallRecorder",
  lang: "en-ca",
  cleanUrls: true,
  description: "Free, FOSS and privacy friendly Android call recording. Empowers ADB to record phone calls on non-rooted device!",
  base: "/ShizuCallRecorder/", // We overwrite the root "/" base for "/repo-name/" due to how github pages work. CASE SENSITIVE
  sitemap: { // Optional, create a sitemap.xml file
    hostname: "https://kitsumed.github.io/ShizuCallRecorder/",
    lastmodDateOnly: false,
  },
  head: [

    // Open Graph / Social Media Meta Tags
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'ShizuCallRecorder' }],
    ['meta', { property: 'og:title', content: 'ShizuCallRecorder | Free and Open-Source Android Call Recorder' }],
    ['meta', { property: 'og:description', content: 'Record calls securely and reliably on Android using ADB without root.' }],
    ['meta', { property: 'og:image', content: 'https://raw.githubusercontent.com/kitsumed/ShizuCallRecorder/main/fastlane/metadata/android/en-US/images/featureGraphic.png' }], // Replace with your hosted banner
    ['meta', { property: 'og:url', content: 'https://kitsumed.github.io/ShizuCallRecorder' }],
    ['meta', { property: 'og:locale', content: 'en_CA' }],

    // Twitter Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'ShizuCallRecorder' }],
    ['meta', { name: 'twitter:description', content: 'FOSS call recording app for Android using ADB.' }],
    ['meta', { name: 'twitter:image', content: 'https://raw.githubusercontent.com/kitsumed/ShizuCallRecorder/main/fastlane/metadata/android/en-US/images/featureGraphic.png' }],
    ['meta', { name: 'twitter:creator', content: '@kitsumed' }],

    ['meta', { name: 'robots', content: 'index, follow' }],
    ['link', { rel: 'icon', type: 'image/png', href: 'favicon-96x96.png' }]
  ],
  themeConfig: {
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
            text: 'Troubleshooting',
            collapsed: false,
            link: '/troubleshooting/',
            items: [
              { text: 'Permissions Granting Issues', link: '/troubleshooting/permissions-granting-issues' },
              { text: 'Background Application Startup Failures', link: '/troubleshooting/background-application-startup-failures' },
              { text: 'Silent Recording or Missing One Side', link: '/troubleshooting/silent-recording-or-missing-one-side' },
              { text: 'Integration Into Native OEM Dialer', link: '/troubleshooting/integration-into-native-oem-dialer' },
            ]
          }
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
})
