importScripts('config.js', 'sites.js');
chrome.storage.local.setAccessLevel({accessLevel: 'TRUSTED_CONTEXTS'});

// The device token lives in storage. A token baked into config.js by an older
// private package is moved there once; an empty stored token means unpaired.
async function deviceToken() {
  const {token} = await chrome.storage.local.get('token');
  if (typeof token === 'string') return token;
  await chrome.storage.local.set({token: BIRNEFES_CONFIG.token || ''});
  return BIRNEFES_CONFIG.token || '';
}

// Leaving the account would also leave its guardian, so only the server can allow it.
async function unpairRefusal(token) {
  if (!token) return null;
  const response = await fetch(`${BIRNEFES_CONFIG.url}/v1/status`, {
    headers: {Authorization: `Bearer ${token}`},
    cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000)
  });
  if (response.status === 401) return null;
  if (!response.ok) throw new Error('server');
  const {guard} = await response.json();
  return guard?.locked === false ? null : 'locked';
}

async function account(message) {
  const token = await deviceToken();
  if (message.operation === 'account') return {paired: !!token};
  if (message.operation === 'unpair') {
    const refusal = await unpairRefusal(token);
    if (refusal) return {error: refusal};
    await chrome.storage.local.set({token: ''});
    return {paired: false};
  }
  // Accounts are created on the phone: a new one here would escape the guardian and the quota.
  const join = message.operation === 'pair';
  if (!join && !token) return {error: 'unpaired'};
  const body = {code: String(message.code || ''), kind: 'chrome'};
  const response = await fetch(`${BIRNEFES_CONFIG.url}/v1/${message.operation}`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json', ...(!join ? {Authorization: `Bearer ${token}`} : {})},
    ...(join ? {body: JSON.stringify(body)} : {}),
    cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000)
  });
  if (response.status === 403 && join) return {error: 'invalid_code'};
  if (response.status === 401) return {error: 'unpaired'};
  if (!response.ok) throw new Error('server');
  const result = await response.json();
  if (join) {
    if (typeof result.token !== 'string' || !result.token) throw new Error('protocol');
    await chrome.storage.local.set({token: result.token});
    return {paired: true};
  }
  if (typeof result.code !== 'string' || !Number.isFinite(result.expiresAt)) throw new Error('protocol');
  // Measure the lifetime on the server clock so a skewed computer clock does not matter.
  const ttl = result.expiresAt - Date.parse(response.headers.get('Date'));
  return {code: result.code, ttl: Number.isFinite(ttl) ? Math.min(600000, Math.max(0, ttl)) : 600000};
}

// A self-imposed limit: it can only be started or lengthened here; ending it early needs the guardian.
const EXTRA_MINUTES = [30, 60, 120, 240, 'day'];
async function extra(message) {
  const start = message.operation === 'extra';
  if (start ? !['video','all'].includes(message.scope) || !EXTRA_MINUTES.includes(message.minutes)
      : message.reason !== undefined && (typeof message.reason !== 'string' || message.reason.length > 200)) return {error: 'invalid'};
  const token = await deviceToken();
  if (!token) return {error: 'unpaired'};
  const body = start ? {scope: message.scope, minutes: message.minutes} : {reason: (message.reason || '').trim()};
  const response = await fetch(`${BIRNEFES_CONFIG.url}/v1/${message.operation}`, {
    method: 'POST',
    headers: {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'},
    body: JSON.stringify(body),
    cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000)
  });
  if (response.status === 401) return {error: 'unpaired'};
  if (response.status === 409 || response.status === 429) {
    const {error} = await response.json().catch(() => ({}));
    if (typeof error === 'string') return {error};
  }
  if (!response.ok) throw new Error('server');
  const result = await response.json();
  if (!Number.isFinite(result.extraAllUntil) || !Number.isFinite(result.extraVideoUntil)) throw new Error('protocol');
  return {extraAllUntil: result.extraAllUntil, extraVideoUntil: result.extraVideoUntil, extraEndPending: result.extraEndPending === true};
}

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
  if (['account','pair','pair-code','unpair'].includes(message?.operation)) {
    if (!fromExtension) return false;
    account(message).then(reply, () => reply({error: 'offline'}));
    return true;
  }
  if (['extra','extra-end'].includes(message?.operation)) {
    if (!fromExtension) return false;
    extra(message).then(reply, () => reply({error: 'offline'}));
    return true;
  }
  if (!['enter','complete','status','emergency','video-enter','video-tick','video-stop','video-emergency','video-status'].includes(message?.operation)) return false;
  if (!fromTarget && !['status','video-status'].includes(message.operation)) return false;
  const isVideo = message.operation.startsWith('video-');
  if (fromTarget && isVideo !== (birNefesCategory(sender.url) === 'video')) return false;
  (async () => {
    const token = await deviceToken();
    if (!token) return {error: 'unpaired'};
    const operation = message.operation;
    const readOnly = operation === 'status' || operation === 'video-status';
    const started = performance.now();
    let response;
    for (let tries=0; tries < (readOnly ? 1 : 2); tries++) {
    try { response = await fetch(`${BIRNEFES_CONFIG.url}/v1/${operation}`, {
      method: readOnly ? 'GET' : 'POST',
      headers: {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'},
      ...(!readOnly ? {body: JSON.stringify({attempt: message.attempt})} : {}),
      cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000)
    });
    break;
    } catch (error) { if (tries === (readOnly ? 0 : 1)) throw error; }
    }
    if (response.status === 401) return {error: 'unpaired'};
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
