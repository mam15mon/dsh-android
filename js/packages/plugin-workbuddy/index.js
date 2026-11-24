// @dsh-android/plugin-workbuddy — WorkBuddy (Tencent CodeBuddy) as a model route
// (docs/ENGINE-CONTRACT.md §2.8).
//
// The route itself is an ordinary llm-service-lite provider the app writes (`openai-chat-completions`
// at `https://copilot.tencent.com/v2` or `https://www.workbuddy.ai/v2`). What the gateway needs on
// top is done here, below the adapter: a `fetch` wrapper signs the chats sent to those two URLs
// (client headers, the account's token refreshed when it is near its end, an API key sent the way
// WorkBuddy's CLI sends one) and rewrites the request spellings and the prompt lines the gateway
// refuses (core.js). The sign-in is made in the browser and kept in `$DSH_HOME/android/workbuddy/`,
// written only by this plugin.
//
// Methods (app → engine), params/results are JSON, `site` is `workbuddy` or `workbuddy-ai`:
//   workbuddy/state        {site}          → { signedIn, name, expired, accounts, attempt }   (no network)
//   workbuddy/usage        {site}          → the same with every account's credits read afresh
//   workbuddy/sign-in/start {site}         → attempt (adds an account; signing in as one already there renews it) { id, phase: 'waiting', url }   (open `url` in a browser)
//   workbuddy/sign-in/cancel {site}        → state
//   workbuddy/sign-out     {site, account?} → state     (one account, or every account of the build)
//   workbuddy/models       {site, key?}    → { models: [{ id, name, context?, output?, images }] }
//   workbuddy/probe        {site, key?, model, forceTool?} → { status, body }   (the connection test's chat, SSE text)
// Notification (engine → app): workbuddy.changed {site, state} when a sign-in attempt settles.
import { join } from 'node:path'
import { createClient, probeSystemFor } from './core.js'
import { systemPromptSection } from '@dsh-android/plugin-guard'

export const name = 'android-workbuddy'

function log(level, message) {
  process.stderr.write(`[android-workbuddy] ${level === 'info' ? '' : `${level}: `}${message}\n`)
}

export function apply(ctx, config = {}) {
  const home = process.env.DSH_HOME
  const dir = typeof config.dir === 'string' && config.dir ? config.dir : home ? join(home, 'android', 'workbuddy') : undefined
  if (dir === undefined) { log('warn', 'DSH_HOME is not set; WorkBuddy routes are not served'); return }
  const notify = (method, params = {}) => ctx.emit('sdk-extensions/notify', method, params)
  const real = globalThis.fetch
  const client = createClient({
    dir,
    fetch: real,
    probeSystem: model => `${probeSystemFor(model)}\n\n${systemPromptSection(process.env.DSH_ANDROID_LOCALE)}`,
    log: m => log('warn', m),
    onChange: site => notify('workbuddy.changed', { site, state: client.state(site) }),
  })

  // Every chat to a WorkBuddy site goes through the client; the rest of fetch is untouched.
  const wrapped = client.wrapFetch(real)
  globalThis.fetch = wrapped
  ctx.effect(() => () => { if (globalThis.fetch === wrapped) globalThis.fetch = real }, 'android-workbuddy: fetch')

  const site = params => {
    const id = params?.site
    if (typeof id !== 'string' || client.SITES[id] === undefined) throw new TypeError(`workbuddy: site must be one of ${Object.keys(client.SITES).join(', ')}`)
    return id
  }
  const methods = new Map([
    ['workbuddy/state', params => client.state(site(params))],
    ['workbuddy/sign-in/start', params => client.signInStart(site(params))],
    ['workbuddy/sign-in/cancel', params => { client.signInCancel(site(params)); return client.state(site(params)) }],
    ['workbuddy/usage', params => client.usage(site(params))],
    ['workbuddy/sign-out', params => { client.signOut(site(params), typeof params?.account === 'string' ? params.account : undefined); return client.state(site(params)) }],
    ['workbuddy/models', async params => ({ models: await client.models(site(params), { key: typeof params?.key === 'string' ? params.key : undefined }) })],
    ['workbuddy/probe', params => client.probe(site(params), { key: typeof params?.key === 'string' ? params.key : undefined, model: params?.model, forceTool: params?.forceTool !== false })],
  ])
  ctx.on('sdk-extensions/resolve', method => methods.get(method))
}
