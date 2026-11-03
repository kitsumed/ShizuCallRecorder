---
# https://vitepress.dev/reference/default-theme-home-page
layout: home

head:
  - - meta
    - name: keywords
      content: >-
        android call recorder, shizuku call recorder, foss android recorder, 
        automatic call recorder, open source call recorder, no-root call recorder android, 
        privacy friendly call recorder, no-ads call recorder, github call recorder, 
        background call recorder, android 14 call recorder, android 15 call recorder
        android 13 call recorder, android 16 call recorder, android 12 call recorder
        android 11+ call recorder, free android call recorder, safe call recorder android,
        clear audio call recorder, third-party app call recorder, voip call recorder android

hero:
  name: "ShizuCallRecorder"
  text: "Free, FOSS"
  tagline: Empowers ADB on Android 11+ to record phone calls on non-rooted device!
  image:
    src: https://raw.githubusercontent.com/kitsumed/ShizuCallRecorder/main/fastlane/metadata/android/en-US/images/icon.png
    alt: ShizuCallRecorder Logo
  actions:
    - theme: brand
      text: Getting Started
      link: /SUPPORT
    - theme: alt
      text: 📥 Download via Github
      link: https://github.com/kitsumed/ShizuCallRecorder/releases/latest
      
    - theme: alt
      text: 📦 Download via F-Droid
      link: https://f-droid.org/packages/com.kitsumed.shizucallrecorder/

features:
  - icon: 🛡️
    title: No Root Required
    details: Empowers ADB (shell privileges) through Shizuku to execute our recording logic without requiring a rooted device.
    
  - icon: 🎧
    title: Stereo Uplink & Downlink
    details: Captures both sides of phone calls (incoming and outgoing) with separate uplink and downlink audio inside a stereo recording.

  - icon: 🔄
    title: Cellular & VoIP Support
    details: Primarily targets Cellular/VoLTE calls while offering best-effort support for Wi-Fi calling and VoIP apps for Android 14+.
    linkText: Troubleshoot Capture Issues
    link: /troubleshooting/silent-recording-or-missing-one-side

  - icon: ⚡
    title: Event-Driven & Lightweight
    details: Runs exclusively on phone event changes with no heavy persistent background processes or annoying persistent notifications.

  - icon: ⚙️
    title: Flexible Codecs & Rules
    details: Choose from multiple codecs (Opus, AAC, HE-AAC-v1) and set up automatic recording exclusion rules for specific contacts or anonymous callers.

  - icon: 🔒
    title: Privacy First & Secure
    details: 100% open-source (GPL-3.0), with immutable and attested releases. Built-in security toggles to manage the Shizuku and minimize possible attack surfaces.
    linkText: Verify Attestations
    link: https://github.com/kitsumed/ShizuCallRecorder/attestations
---

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { VPTeamMembers } from 'vitepress/theme'

// Main team members
const baseTeamMembers = [
  {
    avatar: 'https://avatars.githubusercontent.com/u/45147847',
    name: 'Kitsumed',
    title: 'Head Maintainer',
    desc: 'Author & Copyright Owner',
    sponsor: 'https://github.com/sponsors/kitsumed',
    links: [
      { icon: 'github', link: 'https://github.com/kitsumed' }
    ]
  }
]

const allTeamMembers = ref([...baseTeamMembers])
const showAll = ref(false)
const extraWrapperRef = ref(null)

// First 16 members show immediately
const initialMembers = computed(() => allTeamMembers.value.slice(0, 16))
// Remaining members rendered inside the smooth transition block
const extraMembers = computed(() => allTeamMembers.value.slice(16))

const hasMore = computed(() => allTeamMembers.value.length > 16)

// Custom animated smooth scroll using requestAnimationFrame with responsive bounds
const smoothScrollBy = (targetPx, duration = 700) => {
  const startY = window.scrollY
  const startTime = performance.now()

  const scrollStep = (currentTime) => {
    const elapsed = currentTime - startTime
    const progress = Math.min(elapsed / duration, 1)
    const easeProgress = 1 - Math.pow(1 - progress, 3)
    
    window.scrollTo(0, startY + targetPx * easeProgress)

    if (progress < 1) {
      requestAnimationFrame(scrollStep)
    }
  }

  requestAnimationFrame(scrollStep)
}

const handleShowMore = async () => {
  showAll.value = true

  // Wait for DOM to render the new content
  await nextTick()

  if (extraWrapperRef.value) {
    const contentHeight = extraWrapperRef.value.scrollHeight
    const viewportHeight = window.innerHeight

    // On smaller screens (mobile), scroll less so we don't jump past the content.
    // On desktops, scroll roughly 50-60% of the expanded block height to frame it nicely.
    const scrollMultiplier = viewportHeight < 768 ? 0.35 : 0.55
    const targetScroll = Math.min(contentHeight * scrollMultiplier, viewportHeight * 0.7)

    smoothScrollBy(targetScroll, 700)
  }
}

onMounted(async () => {
  const CACHE_KEY = 'contributors_cache'
  const CACHE_TIME_KEY = 'contributors_time'
  const TWENTY_FOUR_HOURS = 24 * 60 * 60 * 1000

  try {
    const cachedData = localStorage.getItem(CACHE_KEY)
    const cachedTime = localStorage.getItem(CACHE_TIME_KEY)
    const now = new Date().getTime()

    if (cachedData && cachedTime && (now - parseInt(cachedTime, 10) < TWENTY_FOUR_HOURS)) {
      const parsedContributors = JSON.parse(cachedData)
      allTeamMembers.value = [...baseTeamMembers, ...parsedContributors]
      return
    }

    let fetchedContributors = []

    // Try fetching stats contributors first (includes co-authors & totals for sorting)
    try {
      const statsResponse = await fetch('https://api.github.com/repos/kitsumed/shizucallrecorder/stats/contributors')
      
      if (statsResponse.ok && statsResponse.status !== 202) {
        const statsData = await statsResponse.json()
        if (Array.isArray(statsData)) {
          statsData.sort((a, b) => (b.total || 0) - (a.total || 0))

          const existingNames = baseTeamMembers.map(m => m.name.toLowerCase())

          fetchedContributors = statsData
            .map(item => item.author)
            .filter((user) => user && user.login && !existingNames.includes(user.login.toLowerCase()))
            .map((user) => ({
              avatar: user.avatar_url,
              name: user.login,
              title: 'Contributor',
              links: [
                { icon: 'github', link: user.html_url}
              ]
            }))
        }
      }
    } catch (statsError) {
      console.warn('Stats contributors API failed, falling back to standard contributors endpoint.', statsError)
    }

    // Fallback to standard contributors API
    if (fetchedContributors.length === 0) {
      const fallbackResponse = await fetch('https://api.github.com/repos/kitsumed/shizucallrecorder/contributors?per_page=100')
      
      if (!fallbackResponse.ok) {
        throw new Error(`GitHub API error: ${fallbackResponse.statusText}`)
      }
      
      const data = await fallbackResponse.json()

      if (Array.isArray(data)) {
        const existingNames = baseTeamMembers.map(m => m.name.toLowerCase())

        fetchedContributors = data
          .filter((user) => user && user.login && !existingNames.includes(user.login.toLowerCase()))
          .map((user) => ({
            avatar: user.avatar_url,
            name: user.login,
            title: 'Contributor',
            links: [
              { icon: 'github', link: user.html_url }
            ]
          }))
      }
    }

    localStorage.setItem(CACHE_KEY, JSON.stringify(fetchedContributors))
    localStorage.setItem(CACHE_TIME_KEY, now.toString())

    allTeamMembers.value = [...baseTeamMembers, ...fetchedContributors]

  } catch (error) {
    console.warn('API request failed or rate-limited. Falling back to cache or base team.', error)

    const staleCache = localStorage.getItem(CACHE_KEY)
    if (staleCache) {
      try {
        const parsedStale = JSON.parse(staleCache)
        allTeamMembers.value = [...baseTeamMembers, ...parsedStale]
        return
      } catch (e) {
        console.warn('Failed to load cached team member as API fallback.', e)
      }
    }
  }
})
</script>

<br>

<div id="contributors-section">
  <h2>Team & Contributors</h2>
  <p>
    Thank you to everyone who has contributed to this project! (List Generated via GitHub API. It may be incomplete).
  </p>

  <!-- Initial 16 members rendered immediately -->
  <VPTeamMembers size="small" :members="initialMembers" />

  <!-- Remaining members smoothly revealed with transition -->
  <Transition name="expand-fade">
    <div v-if="showAll" ref="extraWrapperRef" class="extra-wrapper">
      <VPTeamMembers size="small" :members="extraMembers" />
    </div>
  </Transition>

  <div v-if="hasMore && !showAll" style="text-align: center; margin-top: 24px;">
    <button @click="handleShowMore" class="show-more-btn">
      Show More Contributors ({{ allTeamMembers.length - 16 }} more)
    </button>
  </div>
</div>

<style scoped>
.show-more-btn {
  background-color: var(--vp-button-alt-bg);
  border: 1px solid var(--vp-button-alt-border);
  color: var(--vp-button-alt-text);
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.25s, border-color 0.25s, transform 0.2s;
}

.show-more-btn:hover {
  background-color: var(--vp-button-alt-hover-bg);
  border-color: var(--vp-button-alt-hover-border);
  transform: translateY(-2px);
}
</style>