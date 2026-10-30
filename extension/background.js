importScripts('config.js', 'sites.js');
chrome.storage.local.setAccessLevel({accessLevel: 'TRUSTED_CONTEXTS'});

chrome.runtime.onMessage.addListener((message, sender, reply) => {
  const fromTarget = sender.tab && birNefesTarget(sender.url);
  const fromExtension = sender.url?.startsWith(chrome.runtime.getURL(''));
  if (!fromTarget && !fromExtension) return false;
  if (message?.operation === 'audio' && fromTarget && typeof message.blocked === 'boolean') {
    setBlockedAudio(sender.tab.id, message.blocked).then(() => reply({ok:true}), () => reply({error:'audio'}));
    return true;
  }
  if (message?.operation === 'home' && fromTarget) {
    chrome.tabs.update(sender.tab.id, {url: 'chrome://newtab/'}).then(() => reply({ok:true}), () => reply({error:'navigation'}));
    return true;
  }
  if (!['enter','complete','status','emergency','video-enter','video-tick','video-stop','video-emergency','video-status'].includes(message?.operation)) return false;
  if (!fromTarget && !['status','video-status'].includes(message.operation)) return false;
  const isVideo = message.operation.startsWith('video-');
  if (fromTarget && isVideo !== (birNefesCategory(sender.url) === 'video')) return false;
  (async () => {
    if (!BIRNEFES_CONFIG.token) return {error: 'unpaired'};
    const operation = message.operation;
    const readOnly = operation === 'status' || operation === 'video-status';
    const started = performance.now();
    let response;
    for (let tries=0; tries < (readOnly ? 1 : 2); tries++) {
    try { response = await fetch(`${BIRNEFES_CONFIG.url}/v1/${operation}`, {
      method: readOnly ? 'GET' : 'POST',
      headers: {Authorization: `Bearer ${BIRNEFES_CONFIG.token}`, 'Content-Type': 'application/json'},
      ...(!readOnly ? {body: JSON.stringify({attempt: message.attempt})} : {}),
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000)
    });
    break;
    } catch (error) { if (tries === (readOnly ? 0 : 1)) throw error; }
    }
    if (!response.ok) throw new Error('server');
    const result = await response.json();
    if (!Number.isFinite(result.serverNow) || !Number.isFinite(result.expiresAt)) throw new Error('protocol');
    return {...result, transitMs: Math.ceil(performance.now() - started)};
  })().then(reply, () => reply({error: 'offline'}));
  return true;
});

// Keep embedded cross-origin players silent while the blocking screen is up.
// Persist the previous mute setting across MV3 worker restarts and restore it
// when the grant opens or the tab navigates to an unrestricted site.
let mediaQueue = Promise.resolve();
function setBlockedAudio(tabId, blocked) {
  mediaQueue = mediaQueue.catch(() => {}).then(async () => {
    const key = `mute:${tabId}`;
    const saved = (await chrome.storage.session.get(key))[key];
    let tab;
    try { tab = await chrome.tabs.get(tabId); } catch { await chrome.storage.session.remove(key); return; }
    if (blocked && saved === undefined) {
      await chrome.storage.session.set({[key]: tab.mutedInfo?.muted === true});
      await chrome.tabs.update(tabId, {muted: true});
    } else if (!blocked && saved !== undefined) {
      if (tab.mutedInfo?.extensionId === chrome.runtime.id)
        await chrome.tabs.update(tabId, {muted: saved});
      await chrome.storage.session.remove(key);
    }
  });
  return mediaQueue;
}
chrome.tabs.onUpdated.addListener((id, change) => {
  if (change.status === 'loading' || (change.url && !birNefesTarget(change.url))) setBlockedAudio(id, false).catch(() => {});
});
chrome.tabs.onRemoved.addListener(id => chrome.storage.session.remove(`mute:${id}`));
