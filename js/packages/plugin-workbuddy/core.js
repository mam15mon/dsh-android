// @dsh-android/plugin-workbuddy — the pure part: WorkBuddy's sites, the headers its gateway
// wants, the request spellings it accepts, the token store and the sign-in / refresh / config
// calls. No dependency outside `node:` built-ins and no engine API, so it runs under
// `node --test` (js/test/workbuddy.test.mjs).
//
// WorkBuddy is Tencent's CodeBuddy plan. Its gateway only answers requests that look like
// WorkBuddy's own client (a User-Agent it does not know is refused with code 10085), serves
// chat completions streamed only (non-stream: 11101) and refuses a chat whose system or
// assistant text carries a line it knows from another agent (11128). The wire details follow
// magpie-community/plugins `packages/workbuddy` (MIT), which measured them against real
// accounts. See docs/ENGINE-CONTRACT.md §2.8.
import { chmodSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { join } from 'node:path'

export const POLL_MS = 1000
export const SIGN_IN_MS = 5 * 60 * 1000
/** A token this close to its end is refreshed before a request. */
export const EARLY_MS = 60 * 1000
/** How long a refresh's result answers for the refresh token it spent (the token is single-use). */
const KEEP_RENEWED_MS = 60 * 1000

/** The two builds of WorkBuddy: the China one (CodeBuddy plan) and WorkBuddy AI (international). */
export const SITES = {
  workbuddy: {
    id: 'workbuddy', name: 'WorkBuddy', endpoint: 'https://copilot.tencent.com', platform: 'workbuddy',
    // the desktop app (and its bundled CLI) the headers say the request comes from
    app: '5.9.3', cli: '2.165.0', appName: 'WorkBuddy', runtime: 'local-agent',
    keysUrl: 'https://www.workbuddy.cn/profile/keys',
    models: [
      ['auto', 'Auto', 168000], ['hy3', 'Hy3', 192000], ['deepseek-v4.1-flash', 'Deepseek-V4.1-Flash', 1000000, 128000],
      ['glm-5.3', 'GLM-5.3', 1000000], ['glm-5.3-flash', 'GLM-5.3-Flash', 1000000, 131072], ['glm-5.2', 'GLM-5.2', 1000000],
      ['minimax-m3', 'MiniMax-M3', 512000], ['kimi-k3-1', 'Kimi-K3', 1000000], ['kimi-k2.6', 'Kimi-K2.6', 256000],
      ['deepseek-v4-pro', 'Deepseek-V4-Pro', 1000000, 128000],
    ],
  },
  'workbuddy-ai': {
    id: 'workbuddy-ai', name: 'WorkBuddy AI', endpoint: 'https://www.workbuddy.ai', platform: 'workbuddy-ai',
    app: '5.9.3', cli: '2.165.0', appName: 'workbuddy-ai', runtime: 'local-agent',
    keysUrl: 'https://www.workbuddy.ai/profile/keys',
    noImages: /^glm-/,
    models: [
      ['default-model', 'Default', 176000, 24000], ['fast-model', 'Fast', 200000, 32000], ['balanced-model', 'Balanced', 256000],
      ['gpt-5.5', 'GPT-5.5', 1000000], ['gemini-3.5-flash', 'Gemini-3.5-Flash', 1000000, 65536], ['glm-5.3', 'GLM-5.3', 1000000],
      ['kimi-k3', 'Kimi-K3', 1000000, 32000], ['minimax-m3', 'MiniMax-M3', 512000],
    ],
  },
}

/** The site a request URL is the chat endpoint of (`<endpoint>/v2/chat/completions`), else undefined. */
export function chatSiteOf(url, sites = SITES) {
  const u = String(url)
  return Object.values(sites).find(s => u.startsWith(`${s.endpoint}/v2/chat/completions`))
}

export class WBError extends Error {
  constructor(message, { code, status } = {}) {
    super(message)
    this.name = 'WBError'
    this.code = code
    this.status = status
  }
}

/** The gateway's refusal of a restricted account: code 11140 / "restricted due to unusual activity". */
export const RESTRICTED_HINT = 'WorkBuddy restricted this account for unusual activity; it stays unusable until WorkBuddy lifts that (an appeal: Submit feedback in WorkBuddy)'
const RESTRICTED_WORDS = /account has been restricted due to unusual activity|账号存在异常行为|帳號存在異常行為/i

export function isRestricted(status, body) {
  if (status < 400) return false
  let v
  try { v = JSON.parse(body) } catch { v = undefined }
  if (v && typeof v === 'object') {
    for (const c of [v.code, v.data?.code, v.error?.code]) if (String(c) === '11140') return true
  }
  return RESTRICTED_WORDS.test(body)
}

// ── what the gateway reads ──────────────────────────────────────────────────

/** `WorkBuddy/<app> <appName>/<app> CLI/<cli>`, the User-Agent of WorkBuddy's own client. */
export function userAgentOf(site) {
  return `WorkBuddy/${site.app} ${site.appName}/${site.app} CLI/${site.cli}`
}

/**
 * The account a request is sent as. `{ key }` is an API key (made on the build's keys page): sent
 * as WorkBuddy's CLI sends one, twice (`X-API-Key` and the bearer) with a user id of `anonymous_`
 * and the key's last eight characters. `{ access, uid, domain, enterpriseId }` is a sign-in.
 */
export function keyAccount(key) {
  const k = typeof key === 'string' ? key.trim() : ''
  return k ? { key: k, access: k, uid: `anonymous_${k.slice(-8)}`, source: 'key' } : undefined
}

export function enterpriseHeaders(a) {
  const id = typeof a?.enterpriseId === 'string' ? a.enterpriseId.trim() : ''
  return id ? { 'X-Enterprise-Id': id, 'X-Tenant-Id': id } : {}
}

function domainOf(site, a) {
  return a.domain || new URL(site.endpoint).host
}

/** Sets the client and account headers of one API call on [headers] (a `Headers`). */
export function sign(site, a, headers, { chat = false } = {}) {
  headers.set('Authorization', `Bearer ${a.access}`)
  headers.set('X-User-Id', a.uid ?? '')
  if (a.source === 'key') {
    headers.set('X-API-Key', a.key)
    headers.delete('X-Domain')
  } else {
    headers.delete('X-API-Key')
    headers.set('X-Domain', domainOf(site, a))
  }
  headers.set('X-Product', 'SaaS')
  headers.set('X-IDE-Type', 'WorkBuddy')
  headers.set('X-IDE-Name', 'WorkBuddy')
  headers.set('X-IDE-Version', site.app)
  headers.set('X-Agent-Intent', 'craft')
  headers.set('X-Agent-Purpose', 'conversation')
  headers.set('X-Requested-With', 'XMLHttpRequest')
  headers.set('User-Agent', userAgentOf(site))
  for (const [k, v] of Object.entries(enterpriseHeaders(a))) headers.set(k, v)
  if (chat) {
    headers.set('X-Agent-Type', 'main')
    if (site.runtime) headers.set('X-Agent-Runtime', site.runtime)
  }
}

// ── the request body ────────────────────────────────────────────────────────

/**
 * Lines the gateway refuses a chat for (error 11128, "Illegal API invocation from an unapproved
 * channel"), each with words that say the same and pass. The check reads system and assistant
 * messages and matches a known line whole, so one more character gets past it; magpie's plugin
 * measured these against both builds. DSH's own opening line is the first.
 */
export const FLAGGED = [
  [/x-anthropic-billing-header:[^\n]*\n?/gi, ''],
  [/You are an AI agent powered by DeepSeek Harness\.?/gi, 'You are an AI agent, powered by DeepSeek Harness.'],
  [/You are Claude Code, Anthropic['’]s official CLI for Claude/gi, "You are Claude Code, Anthropic's CLI for Claude"],
  [/Main branch \(you will usually use this for PRs\)/gi, 'Main branch (usually the base for PRs)'],
  [/(?<!the )Codex CLI is an open source project led by OpenAI\./gi, 'The Codex CLI is an open source project led by OpenAI.'],
  [/Web GUI whose URL the runtime context names\.\s+When the user refers/gi, 'Web GUI whose URL the runtime context names. The interface, when the user refers'],
]

function unflagged(text) {
  for (const [re, to] of FLAGGED) text = text.replace(re, to)
  return text
}

/** Rewords FLAGGED's lines in every message; drops a system message left empty. True when it changed any. */
export function unflag(messages) {
  let changed = false
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]
    if (typeof m?.content === 'string') {
      const t = unflagged(m.content)
      if (t === m.content) continue
      changed = true
      if (t.trim() || m.role !== 'system') m.content = t
      else messages.splice(i, 1)
    } else if (Array.isArray(m?.content)) {
      const parts = []
      let here = false
      for (const p of m.content) {
        const t = typeof p?.text === 'string' ? unflagged(p.text) : null
        if (t === null || t === p.text) { parts.push(p); continue }
        here = true
        if (t.trim()) parts.push({ ...p, text: t })
      }
      if (!here) continue
      changed = true
      if (parts.length || m.role !== 'system') m.content = parts
      else messages.splice(i, 1)
    }
  }
  return changed
}

/**
 * The spellings the gateway takes: `max_tokens` (not `max_completion_tokens`), `system` (not
 * `developer`), an assistant turn's thinking as `reasoning` (it drops `reasoning_content`), and a
 * system message first (its default one when the chat has none). Mutates [body]; true when it did.
 */
export function fixChat(body) {
  if (!Array.isArray(body?.messages) || body.messages.length === 0) return false
  let changed = unflag(body.messages)
  if (body.max_completion_tokens !== undefined) {
    if (body.max_tokens === undefined) body.max_tokens = body.max_completion_tokens
    delete body.max_completion_tokens
    changed = true
  }
  for (const m of body.messages) {
    if (m?.role === 'developer') { m.role = 'system'; changed = true }
    if (m?.role === 'assistant' && (m.reasoning_content !== undefined || m.reasoning_details !== undefined)) {
      const text = typeof m.reasoning_content === 'string' && m.reasoning_content ? m.reasoning_content
        : Array.isArray(m.reasoning_details) ? m.reasoning_details.map(d => (typeof d?.text === 'string' ? d.text : typeof d?.summary === 'string' ? d.summary : '')).join('') : ''
      if (text && !(typeof m.reasoning === 'string' && m.reasoning)) m.reasoning = text
      delete m.reasoning_content
      delete m.reasoning_details
      changed = true
    }
    // Kimi K3.1 Pro refuses an assistant turn that only calls tools and has no text: a space passes.
    if (m?.role === 'assistant' && /^kimi-k3\.1-pro(?:-|$)/i.test(String(body.model ?? '')) && Array.isArray(m.tool_calls) && m.tool_calls.length
      && (m.content === null || m.content === undefined || m.content === '')) { m.content = ' '; changed = true }
  }
  if (body.messages[0]?.role !== 'system') {
    body.messages.unshift({ role: 'system', content: 'You are a helpful assistant.' })
    changed = true
  }
  return changed
}

/** [fixChat] over a JSON text; anything that is not a chat object comes back as it was. */
export function fixChatText(text) {
  if (typeof text !== 'string') return text
  try {
    const body = JSON.parse(text)
    return fixChat(body) ? JSON.stringify(body) : text
  } catch { return text }
}

// ── the gateway's API ───────────────────────────────────────────────────────

/** A call of WorkBuddy's plugin API, which answers `{code, msg, data}`; a code but 0 is an error. */
export async function call(site, method, path, headers = {}, body, { fetch = globalThis.fetch, timeoutMs = 30000 } = {}) {
  const res = await fetch(site.endpoint + path, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'User-Agent': `WorkBuddy/${site.app}`, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  const text = await res.text()
  let env = {}
  try { env = JSON.parse(text) } catch { /* not JSON */ }
  const code = Number.parseInt(env.code, 10) || 0
  if (!res.ok) {
    const why = env.msg || env.message || env.error?.message || text.slice(0, 200) || res.statusText
    throw new WBError(`${site.name}: ${why} (HTTP ${res.status}${env.code ? `, code ${env.code}` : ''})`, { code, status: res.status })
  }
  if (code) throw new WBError(`${site.name}: ${env.msg || 'error'} (${code})`, { code, status: res.status })
  return env.data
}

/** Merges the tokens a sign-in or refresh gave into the stored account (times as at-ms or in-seconds). */
export function merge(a, got, now = Date.now()) {
  const out = { ...a, access: got.accessToken }
  if (got.refreshToken) out.refresh = got.refreshToken
  if (got.domain) out.domain = got.domain
  if (got.tokenType) out.tokenType = got.tokenType
  if (got.expiresAt > 0) out.expires = got.expiresAt
  else if (got.expiresIn > 0) out.expires = now + got.expiresIn * 1000
  if (got.refreshExpiresAt > 0) out.refreshExpiresAt = got.refreshExpiresAt
  else if (got.refreshExpiresIn > 0) out.refreshExpiresAt = now + got.refreshExpiresIn * 1000
  return out
}

const hex = () => randomBytes(16).toString('hex')
const sleep = (ms, signal) => new Promise((resolve, reject) => {
  const t = setTimeout(resolve, ms)
  signal?.addEventListener('abort', () => { clearTimeout(t); reject(new Error('cancelled')) }, { once: true })
})

// ── credits ─────────────────────────────────────────────────────────────────

/** A capacity the billing API sends, as a string ("438.88") or now and then a number; empty is 0. */
function capacity(v) {
  if (v == null || v === '') return 0
  const n = typeof v === 'number' ? v : Number(String(v).trim())
  return Number.isFinite(n) ? n : 0
}

/**
 * An account's credits from its billing summary (`/billing/meter/get-user-resource-summary`): what the
 * plan grants this cycle and what is used, plus prepaid packages that state only what is left.
 * `left` is what the account can still spend; `null` fields mean the summary did not say.
 */
export function creditsOf(sum) {
  const hasTotal = p => p?.CycleTotalCapacity != null && p.CycleTotalCapacity !== ''
  const groups = (Array.isArray(sum?.CreditGroups) ? sum.CreditGroups : []).filter(hasTotal)
  const parts = groups.length ? groups : (Array.isArray(sum?.Packages) ? sum.Packages : []).filter(hasTotal)
  let total = 0
  let used = 0
  for (const p of parts) { total += capacity(p.CycleTotalCapacity); used += capacity(p.CycleUsedCapacity) }
  let prepaid = 0
  let bags = 0
  for (const p of sum?.Packages ?? []) {
    if (!hasTotal(p) && p?.CycleRemainCapacity != null && p.CycleRemainCapacity !== '') { prepaid += capacity(p.CycleRemainCapacity); bags++ }
  }
  const known = total > 0 || bags > 0
  return {
    plan: sum?.IsPaidUser ? 'Pro' : 'Free',
    total: total > 0 ? total : null,
    used: total > 0 ? used : null,
    left: known ? Math.max(0, total - used) + prepaid : null,
  }
}

// ── per-model reasoning, from /v3/config ───────────────────────────────────

const EFFORT_ORDER = ['low', 'medium', 'high', 'xhigh', 'max']
/** Models that turn thinking off for `none` though the config says they only reason (measured by magpie's plugin). */
const OFF_TAKEN = new Set(['deepseek-v4.1-flash', 'deepseek-v4.1-flash-sg'])

/** The levels a config model takes: its `reasoning.supportedEfforts`, else low / high / max (and its own default). */
export function effortsOf(d) {
  const said = d?.reasoning?.supportedEfforts
  if (Array.isArray(said) && said.length) return said.filter(e => typeof e === 'string')
  if (d?.supportsReasoning !== true) return []
  const own = d.reasoning?.effort
  const set = new Set(['low', 'high', 'max', ...(typeof own === 'string' && EFFORT_ORDER.includes(own) ? [own] : [])])
  return EFFORT_ORDER.filter(e => set.has(e))
}

/** The level WorkBuddy's own client asks for when the user picked none; undefined when the model's levels do not hold it. */
export function defaultEffortOf(d, efforts) {
  const own = d?.reasoning?.defaultEffort ?? d?.reasoning?.effort
  return typeof own === 'string' && efforts.includes(own) ? own : undefined
}

/** Whether `none` (thinking off) is a level of this model. */
export function canTurnOff(d, id) {
  const canDisable = d?.reasoning?.canDisableThinking
  return (!d?.onlyReasoning && (canDisable == null || canDisable)) || OFF_TAKEN.has(id)
}

/** A chat whose output budget is this small or less was never meant to think (a title, a classification). */
const SMALL_BUDGET = 1024

/**
 * The effort the chat is sent with. DSH's lowest level `minimal` is how the app asks for "thinking off", which the
 * gateway spells `none` (for a model that lists it); a chat that names no level gets the model's own default, as
 * WorkBuddy's client sends it (the gateway does not think without one), unless its budget is small. [info] is the
 * model's `{ efforts, effort }` from the last config read; without it the chat goes as it is. True when changed.
 */
export function applyEffort(body, info) {
  if (!info || typeof body !== 'object') return false
  const named = body.reasoning_effort ?? body.reasoning?.effort
  if (named === 'minimal' && info.efforts?.includes('none')) { body.reasoning_effort = 'none'; return true }
  if (named !== undefined || !info.effort) return false
  const limit = body.max_tokens ?? body.max_completion_tokens
  if (typeof limit === 'number' && limit > 0 && limit <= SMALL_BUDGET) return false
  body.reasoning_effort = info.effort
  return true
}

/** The models of `/v3/config`: the `cli` agent's, plus the enterprise's own (`custom:` ids), with limits and reasoning levels. */
export function modelsOfConfig(site, cfg) {
  const all = Array.isArray(cfg.models) ? cfg.models : []
  const out = []
  const seen = new Set()
  const add = (id, d) => {
    if (typeof id !== 'string' || !id || seen.has(id)) return
    seen.add(id)
    const levels = effortsOf(d)
    const efforts = levels.length ? [...(canTurnOff(d, id) ? ['none'] : []), ...levels] : []
    const effort = levels.length ? defaultEffortOf(d, levels) : undefined
    out.push({
      id,
      name: d?.name || id,
      context: Number.isFinite(d?.maxInputTokens) && d.maxInputTokens > 0 ? d.maxInputTokens : undefined,
      output: Number.isFinite(d?.maxOutputTokens) && d.maxOutputTokens > 0 ? d.maxOutputTokens : undefined,
      images: d?.supportsImages === true && !site.noImages?.test(id),
      // always said for a model the config describes (empty = no reasoning control); absent only in the built-in list
      efforts,
      ...(effort ? { effort } : {}),
    })
  }
  let cli = false
  for (const agent of cfg.agents ?? []) {
    if (agent?.name !== 'cli') continue
    cli = true
    for (const id of agent.models ?? []) add(id, all.find(x => x.id === id))
  }
  if (cli) for (const d of all) if (typeof d?.id === 'string' && d.id.startsWith('custom:')) add(d.id, d)
  return out
}

// ── the client ──────────────────────────────────────────────────────────────

/** How long an account rests after the gateway turned it away, by reason. */
export const REST_MS = { rate: 5 * 60 * 1000, credits: 60 * 60 * 1000, restricted: 24 * 60 * 60 * 1000 }
const USAGE_TTL_MS = 60 * 1000
const CREDITS_WORDS = /insufficient (?:credits?|balance)|credits? (?:are |is )?(?:exhausted|used up|not enough)|not enough credits|额度|余额不足|积分不足/i

export const accountId = a => `${a.uid ?? ''}|${a.enterpriseId ?? ''}`

/**
 * The WorkBuddy client of one engine: the stored sign-ins of each build (`<dir>/<site>.json`, mode 0600, written only
 * here; several accounts per build), refresh, browser sign-in attempts, credits, the model list and the fetch that
 * signs chats and moves on to the next account when one is turned away. [sites], [fetch], [now] and the timings are
 * injectable for tests.
 */
export function createClient({ dir, sites = SITES, fetch = globalThis.fetch, now = () => Date.now(), pollMs = POLL_MS, signInMs = SIGN_IN_MS, onChange = () => {}, log = () => {} } = {}) {
  const fileOf = site => join(dir, `${site.id}.json`)
  const modelsFileOf = site => join(dir, `${site.id}.models.json`)
  const siteOf = id => { const s = sites[id]; if (!s) throw new TypeError(`unknown WorkBuddy site "${id}"`); return s }

  function writeFile(f, value) {
    mkdirSync(dir, { recursive: true, mode: 0o700 })
    const tmp = `${f}.${process.pid}.tmp`
    writeFileSync(tmp, JSON.stringify(value), { mode: 0o600 })
    chmodSync(tmp, 0o600)
    renameSync(tmp, f)
  }

  /** The stored accounts of a site; reads the old single-account file too. */
  function load(site) {
    try {
      const v = JSON.parse(readFileSync(fileOf(site), 'utf8'))
      const list = Array.isArray(v?.accounts) ? v.accounts : v && typeof v.access === 'string' ? [v] : []
      return list.filter(a => a && typeof a.access === 'string' && a.access).map(a => ({ ...a, id: accountId(a) }))
    } catch { return [] }
  }
  function save(site, accounts) {
    if (!accounts.length) { try { unlinkSync(fileOf(site)) } catch { /* none */ } return }
    writeFile(fileOf(site), { accounts })
  }
  /** Replaces the stored copy of one account (a refresh), leaving the rest as other writers left them. */
  function put(site, a) {
    const list = load(site)
    const i = list.findIndex(x => x.id === a.id)
    if (i >= 0) list[i] = a; else list.push(a)
    save(site, list)
  }

  const refreshable = a => !!a.refresh && !(a.refreshExpiresAt && now() >= a.refreshExpiresAt)
  const stale = a => !!a.expires && now() >= a.expires - EARLY_MS

  // A refresh token is spent once: concurrent callers, and any within KEEP_RENEWED_MS, share the result.
  const renewing = new Map()
  const renewed = new Map()
  function renew(site, a) {
    const key = `${site.id}\n${a.refresh}`
    const done = renewed.get(key)
    if (done && now() - done.at < KEEP_RENEWED_MS) return Promise.resolve(done.next)
    let r = renewing.get(key)
    if (!r) {
      r = (async () => {
        const got = await call(site, 'POST', '/v2/plugin/auth/token/refresh', {
          'X-Refresh-Token': a.refresh, 'X-Auth-Refresh-Source': 'plugin', 'X-Domain': domainOf(site, a), ...enterpriseHeaders(a),
        }, {}, { fetch })
        if (!got?.accessToken) throw new WBError(`${site.name} gave no refreshed token`)
        const next = { ...merge(a, got, now()), id: a.id }
        renewed.set(key, { next, at: now() })
        put(site, next)
        return next
      })().finally(() => renewing.delete(key))
      renewing.set(key, r)
    }
    return r
  }

  // ── what is known of each account at run time (reset with the engine) ──
  const resting = new Map() // `${site}\n${id}` → { until, why }
  const expiredIds = new Set() // `${site}\n${id}`: the sign-in is gone (cleared by signing in again)
  const usageCache = new Map() // `${site}\n${id}` → { at, ...credits } | { at, error }
  const lastUsed = new Map() // site id → account id the last chat went to
  const k = (site, id) => `${site.id}\n${id}`
  const restingNow = (site, id) => { const r = resting.get(k(site, id)); return r && r.until > now() ? r : undefined }
  function rest(site, id, ms, why) { resting.set(k(site, id), { until: now() + ms, why }); onChange(site.id) }

  /** A usable token for [a]: refreshed when near its end (or [force]d). Marks the account expired when its sign-in is gone. */
  async function fresh(site, a, { force = false } = {}) {
    if ((force || stale(a)) && refreshable(a)) {
      try { return await renew(site, a) } catch (e) {
        log(`refresh failed: ${e.message}`)
        if (e.status === 401 || /invalid_grant/i.test(e.message)) { expiredIds.add(k(site, a.id)); onChange(site.id); return undefined }
        // not the sign-in's fault (network, 5xx): the old token is used while it lasts, and a forced renewal just did not happen
        return force ? undefined : a
      }
    }
    if (force) { expiredIds.add(k(site, a.id)); onChange(site.id); return undefined }
    return a
  }

  /** The credits of an account, cached for a minute; `error` says why they are unknown. */
  async function creditsFor(site, a, { fresh: wantFresh = false } = {}) {
    const hit = usageCache.get(k(site, a.id))
    if (hit && !wantFresh && now() - hit.at < USAGE_TTL_MS) return hit
    const ask = async (acc) => call(site, 'POST', '/billing/meter/get-user-resource-summary', {
      Authorization: `Bearer ${acc.access}`, 'X-User-Id': acc.uid ?? '', 'X-Domain': domainOf(site, acc), 'X-Product': 'SaaS', 'X-IDE-Type': 'WorkBuddy', ...enterpriseHeaders(acc),
    }, {}, { fetch, timeoutMs: 20000 })
    let out
    try {
      let acc = (await fresh(site, a)) ?? a
      let sum
      try { sum = await ask(acc) } catch (e) {
        if (e.status !== 401) throw e
        acc = await fresh(site, acc, { force: true })
        if (!acc) throw new WBError('the sign-in is no longer valid', { status: 401 })
        sum = await ask(acc)
      }
      const c = creditsOf(sum)
      out = c.left == null && a.enterpriseId ? { at: now(), error: 'credits of an enterprise account are not shown' } : { at: now(), ...c }
    } catch (e) { out = { at: now(), error: e.message } }
    usageCache.set(k(site, a.id), out)
    return out
  }

  // ── choosing an account ──
  const usable = (site, a) => !expiredIds.has(k(site, a.id)) && !restingNow(site, a.id)
  const exhausted = (site, a) => usageCache.get(k(site, a.id))?.left === 0

  /** The account the next chat goes to: the one last used while it can serve, else the one with the most credits left. */
  function pick(site, exclude = new Set()) {
    const list = load(site).filter(a => !exclude.has(a.id) && usable(site, a))
    if (!list.length) return undefined
    const sticky = list.find(a => a.id === lastUsed.get(site.id) && !exhausted(site, a))
    if (sticky) return sticky
    const left = a => { const l = usageCache.get(k(site, a.id))?.left; return l == null ? 1 : l }
    return [...list].sort((x, y) => (exhausted(site, x) ? 1 : 0) - (exhausted(site, y) ? 1 : 0) || left(y) - left(x))[0]
  }

  // ── browser sign-in ──
  const attempts = new Map() // site id → { id, phase, url, error, name, abort }
  const viewOfAttempt = t => t && { id: t.id, phase: t.phase, url: t.url, error: t.error ?? null, name: t.name ?? null }

  async function pollFor(site, path, headers, retry, deadline, signal) {
    for (;;) {
      if (signal.aborted) throw new Error('cancelled')
      if (now() > deadline) throw new Error(`${site.name} sign-in timed out`)
      try {
        const data = await call(site, 'GET', path, headers, undefined, { fetch })
        if (data && Object.keys(data).length) return data
      } catch (e) {
        if (!(retry.includes(e.code) || e.status === 408 || e.status === 429)) throw e
      }
      await sleep(pollMs, signal)
    }
  }

  async function signInStart(siteId) {
    const site = siteOf(siteId)
    attempts.get(siteId)?.abort?.abort()
    const state = await call(site, 'POST', `/v2/plugin/auth/state?platform=${encodeURIComponent(site.platform)}`, {
      'X-No-Authorization': 'true', 'X-No-User-Id': 'true', 'X-No-Enterprise-Id': 'true', 'X-No-Department-Info': 'true',
    }, {}, { fetch })
    if (!state?.state || !state?.authUrl) throw new WBError(`${site.name} gave no sign-in page`)
    const url = new URL(state.authUrl)
    if (url.protocol !== 'https:') throw new WBError(`${site.name}'s sign-in page is not https`)
    url.searchParams.set('version', site.app)
    url.searchParams.set('loginSessionId', hex())
    const abort = new AbortController()
    const attempt = { id: hex(), phase: 'waiting', url: url.toString(), abort }
    attempts.set(siteId, attempt)
    const q = encodeURIComponent(state.state)
    ;(async () => {
      try {
        const deadline = now() + signInMs
        const token = await pollFor(site, `/v2/plugin/auth/token?state=${q}`, {}, [11217], deadline, abort.signal)
        if (!token.accessToken) throw new WBError(`${site.name} sent back no token`)
        const a = merge({}, token, now())
        const who = await pollFor(site, `/v2/plugin/login/account?state=${q}`, {
          Authorization: `Bearer ${a.access}`, 'X-Domain': domainOf(site, a), 'X-No-User-Id': 'true', 'X-No-Enterprise-Id': 'true',
        }, [12151], deadline, abort.signal)
        a.uid = who.uid ?? ''
        // a team / education account the sign-in page chose: asked for as that enterprise
        const enterpriseId = typeof who.enterpriseId === 'string' ? who.enterpriseId.trim() : ''
        if (enterpriseId) a.enterpriseId = enterpriseId
        a.name = [who.nickname || who.phoneNumber || who.uid || site.name, who.enterpriseName].filter(Boolean).join(' · ')
        a.id = accountId(a)
        put(site, a)
        // signing in again brings the account back: it no longer rests or counts as expired
        expiredIds.delete(k(site, a.id)); resting.delete(k(site, a.id)); usageCache.delete(k(site, a.id))
        attempt.phase = 'done'
        attempt.name = a.name
      } catch (e) {
        attempt.phase = abort.signal.aborted ? 'cancelled' : 'failed'
        attempt.error = abort.signal.aborted ? null : e.message
      }
      onChange(siteId)
    })()
    return viewOfAttempt(attempt)
  }

  function signInCancel(siteId) {
    const t = attempts.get(siteId)
    if (t?.phase === 'waiting') t.abort.abort()
  }

  // ── what the app shows ──
  function accountView(site, a) {
    const r = restingNow(site, a.id)
    const u = usageCache.get(k(site, a.id))
    return {
      id: a.id, name: a.name ?? site.name,
      expired: expiredIds.has(k(site, a.id)) || (!!a.expires && now() >= a.expires && !refreshable(a)),
      current: lastUsed.get(site.id) === a.id,
      resting: r ? { until: r.until, why: r.why } : null,
      plan: u?.plan ?? null, left: u?.left ?? null, total: u?.total ?? null, used: u?.used ?? null, creditsError: u?.error ?? null,
    }
  }

  function state(siteId) {
    const site = siteOf(siteId)
    const accounts = load(site).map(a => accountView(site, a))
    const live = accounts.filter(a => !a.expired)
    return {
      site: siteId,
      signedIn: accounts.length > 0,
      name: (accounts.find(a => a.current) ?? live[0] ?? accounts[0])?.name ?? null,
      expired: accounts.length > 0 && live.length === 0,
      accounts,
      attempt: viewOfAttempt(attempts.get(siteId)) ?? null,
    }
  }

  /** [state] with every account's credits read afresh (a network call per account). */
  async function usage(siteId) {
    const site = siteOf(siteId)
    await Promise.all(load(site).map(a => creditsFor(site, a, { fresh: true })))
    return state(siteId)
  }

  /** Signs one account (or, without [id], every account of the build) out: its tokens are deleted. */
  function signOut(siteId, id) {
    const site = siteOf(siteId)
    attempts.get(siteId)?.abort?.abort()
    attempts.delete(siteId)
    const list = id ? load(site).filter(a => a.id !== id) : []
    save(site, list)
    for (const m of [expiredIds, resting, usageCache]) for (const key of [...m.keys()]) if (key.startsWith(`${site.id}\n`) && (!id || key === k(site, id))) m.delete(key)
    onChange(siteId)
  }

  // ── the model list: /v3/config, else the build's known list ──
  function readModelInfo(site) {
    try { return JSON.parse(readFileSync(modelsFileOf(site), 'utf8')) } catch { return {} }
  }

  async function models(siteId, { key } = {}) {
    const site = siteOf(siteId)
    const kacc = keyAccount(key)
    // WorkBuddy's own CLI reads /v3/config with an API key only when it starts ck_ or pt_
    if (kacc && !/^(ck_|pt_)/.test(kacc.key)) return builtin(site)
    let a = kacc
    if (!a) {
      const picked = pick(site) ?? load(site).find(x => !expiredIds.has(k(site, x.id)))
      a = picked && ((await fresh(site, picked)) ?? undefined)
    }
    if (!a) throw new WBError(`${site.name}: sign in first`, { status: 401 })
    const headers = new Headers({ Accept: 'application/json' })
    sign(site, a, headers)
    const res = await fetch(`${site.endpoint}/v3/config`, { headers, signal: AbortSignal.timeout(15000) })
    const env = await res.json().catch(() => ({}))
    if (!res.ok) throw new WBError(`${site.name}'s config: HTTP ${res.status}`, { status: res.status })
    if (env.code) throw new WBError(`${site.name}'s config: ${env.msg} (${env.code})`, { code: Number(env.code) })
    const list = modelsOfConfig(site, env.data ?? {})
    if (!list.length) return builtin(site)
    // the chat path reads each model's levels from here (applyEffort); it never waits on the network
    try { writeFile(modelsFileOf(site), Object.fromEntries(list.map(m => [m.id, { efforts: m.efforts ?? [], effort: m.effort ?? null }]))) } catch (e) { log(`models cache: ${e.message}`) }
    return list
  }

  /** Account-less listing for the app's first look (no sign-in yet): the build's known models. */
  const builtin = site => site.models.map(([id, name, context, output]) => ({ id, name, context, output, images: undefined }))

  /** The app's connection test: one streamed chat with a no-argument tool through [wrapFetch]; `{ status, body }`. */
  async function probe(siteId, { key, model, forceTool = true } = {}) {
    const site = siteOf(siteId)
    if (typeof model !== 'string' || !model) throw new TypeError('workbuddy/probe requires a model')
    const tool = 'dsh_connection_check'
    const body = JSON.stringify({
      model, stream: true, max_tokens: 1024,
      messages: [
        { role: 'system', content: `This is a connection check. Call the ${tool} tool once. Do not reply with text.` },
        { role: 'user', content: `Call ${tool} now.` },
      ],
      tools: [{ type: 'function', function: { name: tool, description: 'Confirms that tool calls work. Takes no arguments.', parameters: { type: 'object', properties: {}, required: [] } } }],
      ...(forceTool ? { tool_choice: { type: 'function', function: { name: tool } } } : {}),
    })
    const headers = { 'content-type': 'application/json', accept: 'text/event-stream', ...(key?.trim() ? { authorization: `Bearer ${key.trim()}` } : {}) }
    const res = await wrapFetch()(`${site.endpoint}/v2/chat/completions`, { method: 'POST', headers, body, signal: AbortSignal.timeout(90000) })
    return { status: res.status, body: (await res.text()).slice(0, 256_000) }
  }

  // ── the chat fetch ──
  /**
   * A `fetch` that signs and fixes chats sent to a site's `/v2/chat/completions` and passes everything else
   * through. A request carrying an `Authorization` bearer is an API key's (one account, no rotation); one without it
   * is the stored sign-ins': the account is picked ([pick]) and, when the gateway turns it away (rate limit, no
   * credits left, restricted, sign-in gone), the same chat goes to the next account that can serve.
   */
  function wrapFetch(real = fetch) {
    return async function workbuddyFetch(input, init) {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input?.url
      const site = chatSiteOf(url, sites)
      if (!site || typeof init?.body !== 'string') return real(input, init)
      const headers = new Headers(init.headers)
      const bearer = /^Bearer\s+(.+)$/i.exec(headers.get('authorization') ?? '')?.[1]
      let chat
      try { chat = JSON.parse(init.body) } catch { chat = undefined }
      let body = init.body
      if (chat && typeof chat === 'object') {
        const changed = [fixChat(chat), applyEffort(chat, readModelInfo(site)[chat.model])]
        if (changed.some(Boolean)) body = JSON.stringify(chat)
      }
      const send = (a) => {
        const h = new Headers(headers)
        sign(site, a, h, { chat: true })
        return real(input, { ...init, headers: h, body })
      }
      const kacc = keyAccount(bearer)
      if (kacc) return answered(site, await send(kacc))

      const tried = new Set()
      let last
      for (;;) {
        const picked = pick(site, tried)
        if (!picked) return last ?? refusal(401, load(site).length ? `${site.name}: every signed-in account is expired or resting; sign in again or wait` : `${site.name}: sign in first (设置 → 模型服务 → ${site.name} → 登录)`)
        tried.add(picked.id)
        let a = await fresh(site, picked)
        if (!a) { last = refusal(401, `${site.name}: the sign-in of ${picked.name ?? 'an account'} expired; sign in again`); continue }
        let res = await send(a)
        if (res.status === 401) {
          res.body?.cancel?.().catch(() => {})
          a = await fresh(site, a, { force: true })
          if (!a) { last = refusal(401, `${site.name}: the sign-in of ${picked.name ?? 'an account'} is no longer valid; sign in again`); continue }
          res = await send(a)
        }
        if (res.ok) {
          lastUsed.set(site.id, picked.id)
          // keep the credits current without making the chat wait
          creditsFor(site, a, { fresh: true }).then(() => onChange(site.id), () => {})
          return res
        }
        const text = await res.text()
        const why = turnedAway(res.status, text)
        if (!why) return new Response(text, { status: res.status, statusText: res.statusText, headers: res.headers })
        rest(site, picked.id, REST_MS[why], why)
        log(`${picked.name ?? picked.id}: ${why} (HTTP ${res.status}); trying the next account`)
        last = refusal(res.status, why === 'restricted' ? `${RESTRICTED_HINT}. ${text.slice(0, 300)}` : text.slice(0, 500), text)
      }
    }
  }

  /** The answer of a key's chat: a restricted account's refusal carries the hint, anything else goes as it came. */
  async function answered(site, res) {
    if (res.ok) return res
    const text = await res.text()
    if (isRestricted(res.status, text)) return refusal(res.status, `${RESTRICTED_HINT}. ${text.slice(0, 300)}`)
    return new Response(text, { status: res.status, statusText: res.statusText, headers: res.headers })
  }

  return { SITES: sites, state, usage, models, probe, signInStart, signInCancel, signOut, wrapFetch, load, pick, account: async (id, opts) => { const site = siteOf(id); const a = pick(site); return a ? fresh(site, a, opts) : undefined } }
}

/** Why the gateway turned an account away (so the next one should serve), or undefined for any other answer. */
export function turnedAway(status, text) {
  if (isRestricted(status, text)) return 'restricted'
  if (status === 429) return 'rate'
  if (status === 402 || (status >= 400 && status < 500 && CREDITS_WORDS.test(text))) return 'credits'
  return undefined
}

function refusal(status, message) {
  return new Response(JSON.stringify({ error: { message, code: status === 401 ? 'unauthorized' : String(status) } }), {
    status, headers: { 'content-type': 'application/json' },
  })
}
