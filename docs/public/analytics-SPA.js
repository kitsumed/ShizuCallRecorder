if (typeof window !== 'undefined') {
  const isLocal = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  let lastTrackedPath = location.pathname;
  let lastUrl = document.referrer || '';

  const logDebug = (msg, detail = '') => {
    if (isLocal) {
      console.log(`[GoatCounter] ${msg}${detail ? '\n' + detail : ''}`);
    }
  };

  const isErrorPage = () => {
    // Check for a specific .code element inside VPContent showing a 4xx or 5xx HTTP status code message
    const codeEl = document.querySelector('#VPContent .code, .VPContent .code');
    if (codeEl) {
      const codeText = codeEl.textContent.trim();
      if (/^[45][0-9]{2}$/.test(codeText)) {
        return true;
      }
    }

    return false;
  };

  const sendHit = (label) => {
    if (!window.goatcounter || typeof window.goatcounter.count !== 'function') {
      logDebug(`Skipped (${label}): goatcounter not loaded yet`);
      return;
    }

    if (isErrorPage()) {
      logDebug(`Skipped (${label}): Error page layout detected (${location.pathname})`);
      return;
    }

    const currentCleanUrl = location.origin + location.pathname;
    const vars = {
      path: location.pathname,
      referrer: lastUrl
    };

    if (isLocal) {
      const rawUrl = window.goatcounter.url(vars);
      logDebug(`Dry Run (${label})`, decodeURIComponent(rawUrl));
    } else {
      window.goatcounter.count(vars);
    }

    lastTrackedPath = location.pathname;
    lastUrl = currentCleanUrl;
  };

  const scheduleTrack = (label) => {
    if (location.pathname === lastTrackedPath) {
      logDebug(`Ignored (${label}): same pathname (${location.pathname})`);
      return;
    }

    const titleEl = document.querySelector('title');
    if (!titleEl) {
      setTimeout(() => sendHit(label), 150);
      return;
    }

    let done = false;
    let fallbackTimer;

    const finish = () => {
      if (done) return;
      done = true;
      observer.disconnect();
      clearTimeout(fallbackTimer);
      sendHit(label);
    };

    const observer = new MutationObserver(finish);
    observer.observe(titleEl, { childList: true, characterData: true, subtree: true });
    fallbackTimer = setTimeout(finish, 300);
  };

  // Record initial page load
  window.addEventListener('load', () => {
    if (isLocal) {
      // GoatCounter will block this localhost, so we send a hit just for local debugging
      setTimeout(() => sendHit('Initial Load'), 100);
    } else {
      lastTrackedPath = location.pathname;
      lastUrl = location.origin + location.pathname;
    }
  });

  // Track SPA link clicks
  const origPushState = history.pushState;
  history.pushState = function (...args) {
    origPushState.apply(this, args);
    scheduleTrack('SPA Navigation');
  };
}