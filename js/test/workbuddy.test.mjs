// plugin-workbuddy core (docs/ENGINE-CONTRACT.md §2.8) against a fake WorkBuddy gateway:
//   node --test js/test/workbuddy.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { mkdtempSync, readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { applyEffort, createClient, creditsOf, effortsOf, fixChat, isRestricted, keyAccount, modelsOfConfig, turnedAway, REST_MS, SITES } from '../packages/plugin-workbuddy/core.js'

const json = (res, status, body) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)) }

/** A fake gateway; [handlers] get (req, body, res) per path and return true when they answered. */
async function gateway(handlers) {
  const seen = []
  const server = http.createServer((req, res) => {
    let raw = ''
    req.on('data', c => { raw += c })
    req.on('end', () => {
      const path = req.url.split('?')[0]
      seen.push({ method: req.method, path, url: req.url, headers: req.headers, body: raw })
      const h = handlers[path]
      if (h) return h(req, raw, res, seen)
      json(res, 404, { code: 404, msg: 'no such path' })
    })
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const endpoint = `http://127.0.0.1:${server.address().port}`
  const sites = { workbuddy: { ...SITES.workbuddy, endpoint }, 'workbuddy-ai': { ...SITES['workbuddy-ai'], endpoint } }
  return { seen, sites, endpoint, close: () => new Promise(r => server.close(r)) }
}

const chat = (extra = {}) => JSON.stringify({ model: 'glm-5.3', stream: true, messages: [{ role: 'system', content: 'You are an AI agent powered by DeepSeek Harness.' }, { role: 'user', content: 'hi' }], ...extra })
const sse = (res) => { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end('data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n') }

test('fixChat rewords the refused line and uses the spellings the gateway takes', () => {
  const body = {
    model: 'x', max_completion_tokens: 100,
    messages: [
      { role: 'developer', content: 'You are an AI agent powered by DeepSeek Harness.' },
      { role: 'assistant', content: 'a', reasoning_content: 'thinking' },
      { role: 'user', content: 'u' },
    ],
  }
  assert.equal(fixChat(body), true)
  assert.equal(body.max_tokens, 100)
  assert.equal(body.max_completion_tokens, undefined)
  assert.equal(body.messages[0].role, 'system')
  assert.equal(body.messages[0].content, 'You are an AI agent, powered by DeepSeek Harness.')
  assert.equal(body.messages[1].reasoning, 'thinking')
  assert.equal(body.messages[1].reasoning_content, undefined)
})

test('fixChat adds a default system message and leaves a clean chat alone', () => {
  const noSystem = { messages: [{ role: 'user', content: 'hi' }] }
  assert.equal(fixChat(noSystem), true)
  assert.equal(noSystem.messages[0].role, 'system')
  const clean = { messages: [{ role: 'system', content: 'be brief' }, { role: 'user', content: 'hi' }], max_tokens: 5 }
  assert.equal(fixChat(clean), false)
})

test('fixChat rewords inside content parts and drops a system message left empty', () => {
  const body = { messages: [
    { role: 'system', content: 'x-anthropic-billing-header: abc\n' },
    { role: 'system', content: [{ type: 'text', text: 'You are an AI agent powered by DeepSeek Harness.' }] },
    { role: 'user', content: 'hi' },
  ] }
  fixChat(body)
  assert.equal(body.messages.length, 2)
  assert.equal(body.messages[0].content[0].text, 'You are an AI agent, powered by DeepSeek Harness.')
})

test('isRestricted reads code 11140 and the words, only on an error status', () => {
  assert.equal(isRestricted(403, '{"code":11140,"msg":"request illegal"}'), true)
  assert.equal(isRestricted(403, '{"error":{"code":"11140"}}'), true)
  assert.equal(isRestricted(403, 'Your account has been restricted due to unusual activity'), true)
  assert.equal(isRestricted(200, '{"code":11140}'), false)
  assert.equal(isRestricted(400, '{"code":11128}'), false)
})

test('keyAccount: a key is the bearer, X-API-Key and the id anonymous_<last 8>', () => {
  assert.deepEqual(keyAccount('  ck_abcdefghijkl '), { key: 'ck_abcdefghijkl', access: 'ck_abcdefghijkl', uid: 'anonymous_efghijkl', source: 'key' })
  assert.equal(keyAccount('  '), undefined)
})

test('an API key chat is signed like WorkBuddy\'s CLI and its body fixed; other URLs pass through', async () => {
  const g = await gateway({ '/v2/chat/completions': (req, body, res) => sse(res), '/other': (req, body, res) => json(res, 200, { ok: true }) })
  try {
    const client = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites })
    const f = client.wrapFetch()
    const res = await f(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer ck_0123456789abcdef', 'content-type': 'application/json', 'user-agent': 'DSH-Android/1 deepseek-harness' }, body: chat({ max_completion_tokens: 50 }) })
    assert.equal(res.status, 200)
    const sent = g.seen.at(-1)
    assert.equal(sent.headers.authorization, 'Bearer ck_0123456789abcdef')
    assert.equal(sent.headers['x-api-key'], 'ck_0123456789abcdef')
    assert.equal(sent.headers['x-user-id'], 'anonymous_89abcdef')
    assert.equal(sent.headers['x-domain'], undefined)
    assert.match(sent.headers['user-agent'], /^WorkBuddy\/5\.9\.3 WorkBuddy\/5\.9\.3 CLI\//)
    assert.equal(sent.headers['x-ide-name'], 'WorkBuddy')
    assert.equal(sent.headers['x-agent-purpose'], 'conversation')
    const body = JSON.parse(sent.body)
    assert.equal(body.max_tokens, 50)
    assert.equal(body.messages[0].content, 'You are an AI agent, powered by DeepSeek Harness.')
    const other = await f(`${g.endpoint}/other`, { method: 'POST', body: '{}' })
    assert.deepEqual(await other.json(), { ok: true })
    assert.equal(g.seen.at(-1).headers['x-api-key'], undefined)
  } finally { await g.close() }
})

test('signed out: a chat without a key is refused with a 401 that says to sign in, nothing is sent', async () => {
  const g = await gateway({})
  try {
    const client = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites })
    const res = await client.wrapFetch()(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: {}, body: chat() })
    assert.equal(res.status, 401)
    assert.match((await res.json()).error.message, /sign in/i)
    assert.equal(g.seen.length, 0)
  } finally { await g.close() }
})

test('browser sign-in: state, poll through "not yet", store the account (0600), then chats use it', async () => {
  let polls = 0
  const g = await gateway({
    '/v2/plugin/auth/state': (req, b, res) => json(res, 200, { code: 0, data: { state: 'S1', authUrl: 'https://login.example/p?x=1' } }),
    '/v2/plugin/auth/token': (req, b, res) => (++polls < 3 ? json(res, 200, { code: 11217, msg: 'not yet' }) : json(res, 200, { code: 0, data: { accessToken: 'AT1', refreshToken: 'RT1', expiresIn: 3600, refreshExpiresIn: 86400, domain: 'copilot.tencent.com' } })),
    '/v2/plugin/login/account': (req, b, res) => json(res, 200, { code: 0, data: { uid: 'u-1', nickname: 'Ada' } }),
    '/v2/chat/completions': (req, b, res) => sse(res),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    let settled
    const done = new Promise(r => { settled = r })
    const client = createClient({ dir, sites: g.sites, pollMs: 5, onChange: settled })
    const attempt = await client.signInStart('workbuddy')
    assert.equal(attempt.phase, 'waiting')
    const url = new URL(attempt.url)
    assert.equal(url.origin, 'https://login.example')
    assert.equal(url.searchParams.get('version'), '5.9.3')
    assert.ok(url.searchParams.get('loginSessionId'))
    assert.equal((g.seen.find(s => s.path === '/v2/plugin/auth/state')).url.includes('platform=workbuddy'), true)
    await done
    const st = client.state('workbuddy')
    assert.equal(st.signedIn, true)
    assert.equal(st.name, 'Ada')
    assert.equal(st.attempt.phase, 'done')
    assert.equal(statSync(join(dir, 'workbuddy.json')).mode & 0o777, 0o600)
    const res = await client.wrapFetch()(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: {}, body: chat() })
    assert.equal(res.status, 200)
    const sent = g.seen.at(-1)
    assert.equal(sent.headers.authorization, 'Bearer AT1')
    assert.equal(sent.headers['x-user-id'], 'u-1')
    assert.equal(sent.headers['x-domain'], 'copilot.tencent.com')
    client.signOut('workbuddy')
    assert.equal(client.state('workbuddy').signedIn, false)
  } finally { await g.close() }
})

test('a sign-in nobody finishes times out as failed; cancel ends it as cancelled', async () => {
  const g = await gateway({
    '/v2/plugin/auth/state': (req, b, res) => json(res, 200, { code: 0, data: { state: 'S', authUrl: 'https://login.example/p' } }),
    '/v2/plugin/auth/token': (req, b, res) => json(res, 200, { code: 11217, msg: 'not yet' }),
  })
  try {
    let n = 0
    const waits = []
    const client = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites, pollMs: 5, signInMs: 40, onChange: () => waits.shift()?.() })
    const wait = () => new Promise(r => waits.push(r))
    let w = wait()
    await client.signInStart('workbuddy')
    await w
    assert.equal(client.state('workbuddy').attempt.phase, 'failed')
    assert.match(client.state('workbuddy').attempt.error, /timed out/)
    w = wait()
    const client2 = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites, pollMs: 5, onChange: () => waits.shift()?.() })
    await client2.signInStart('workbuddy')
    client2.signInCancel('workbuddy')
    await w
    assert.equal(client2.state('workbuddy').attempt.phase, 'cancelled')
    void n
  } finally { await g.close() }
})

function seed(dir, a) {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'workbuddy.json'), JSON.stringify(a))
}

test('a token near its end is refreshed before the chat and the new one stored', async () => {
  const g = await gateway({
    '/v2/plugin/auth/token/refresh': (req, b, res) => json(res, 200, { code: 0, data: { accessToken: 'AT2', refreshToken: 'RT2', expiresIn: 3600 } }),
    '/v2/chat/completions': (req, b, res) => sse(res),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seed(dir, { access: 'AT1', refresh: 'RT1', uid: 'u-1', domain: 'copilot.tencent.com', expires: Date.now() + 1000, refreshExpiresAt: Date.now() + 1e9 })
    const client = createClient({ dir, sites: g.sites })
    const f = client.wrapFetch()
    const [a, b] = await Promise.all([1, 2].map(() => f(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: {}, body: chat() })))
    assert.equal(a.status, 200); assert.equal(b.status, 200)
    const refreshes = g.seen.filter(s => s.path === '/v2/plugin/auth/token/refresh')
    assert.equal(refreshes.length, 1, 'one refresh token is spent once')
    assert.equal(refreshes[0].headers['x-refresh-token'], 'RT1')
    assert.equal(g.seen.filter(s => s.path === '/v2/chat/completions').every(s => s.headers.authorization === 'Bearer AT2'), true)
    assert.equal(JSON.parse(readFileSync(join(dir, 'workbuddy.json'), 'utf8')).accounts[0].refresh, 'RT2')
  } finally { await g.close() }
})

test('a 401 on a chat renews once and sends it again; a refused refresh says to sign in again', async () => {
  let chats = 0
  const g = await gateway({
    '/v2/plugin/auth/token/refresh': (req, b, res) => json(res, 200, { code: 0, data: { accessToken: 'AT2', refreshToken: 'RT2', expiresIn: 3600 } }),
    '/v2/chat/completions': (req, b, res) => (req.headers.authorization === 'Bearer AT2' ? sse(res) : (chats++, json(res, 401, { code: 401, msg: 'expired' }))),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seed(dir, { access: 'AT1', refresh: 'RT1', uid: 'u-1', expires: Date.now() + 3600e3, refreshExpiresAt: Date.now() + 1e9 })
    const client = createClient({ dir, sites: g.sites })
    const res = await client.wrapFetch()(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: {}, body: chat() })
    assert.equal(res.status, 200)
    assert.equal(chats, 1)
  } finally { await g.close() }

  const g2 = await gateway({
    '/v2/plugin/auth/token/refresh': (req, b, res) => json(res, 401, { code: 401, msg: 'invalid_grant' }),
    '/v2/chat/completions': (req, b, res) => json(res, 401, { code: 401, msg: 'expired' }),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seed(dir, { access: 'AT1', refresh: 'RT1', uid: 'u-1', expires: Date.now() + 3600e3, refreshExpiresAt: Date.now() + 1e9 })
    const res = await createClient({ dir, sites: g2.sites }).wrapFetch()(`${g2.endpoint}/v2/chat/completions`, { method: 'POST', headers: {}, body: chat() })
    assert.equal(res.status, 401)
    assert.match((await res.json()).error.message, /sign in again/)
  } finally { await g2.close() }
})

test('a restricted account\'s refusal carries the hint; other errors pass as they came', async () => {
  const g = await gateway({ '/v2/chat/completions': (req, b, res) => json(res, 403, { code: 11140, msg: 'request illegal' }) })
  try {
    const res = await createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites }).wrapFetch()(`${g.endpoint}/v2/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer ck_0123456789abcdef' }, body: chat() })
    assert.equal(res.status, 403)
    assert.match((await res.json()).error.message, /restricted this account for unusual activity/)
  } finally { await g.close() }
  const g2 = await gateway({ '/v2/chat/completions': (req, b, res) => json(res, 400, { code: 11128, msg: 'Illegal API invocation from an unapproved channel' }) })
  try {
    const res = await createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g2.sites }).wrapFetch()(`${g2.endpoint}/v2/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer ck_0123456789abcdef' }, body: chat() })
    assert.equal(res.status, 400)
    assert.equal((await res.json()).code, 11128)
  } finally { await g2.close() }
})

test('models come from /v3/config (cli agent + custom: ids); other keys and the empty case use the build\'s list', async () => {
  const cfg = { code: 0, data: {
    agents: [{ name: 'cli', models: ['glm-5.3', 'kimi-k3'] }, { name: 'other', models: ['zzz'] }],
    models: [
      { id: 'glm-5.3', name: 'GLM-5.3', maxInputTokens: 1000000, maxOutputTokens: 32000, supportsImages: true },
      { id: 'kimi-k3', name: 'Kimi-K3', maxInputTokens: 256000 },
      { id: 'custom:team|team', name: 'Team', maxInputTokens: 100000 },
    ],
  } }
  const g = await gateway({ '/v3/config': (req, b, res) => json(res, 200, cfg) })
  try {
    const client = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites })
    const list = await client.models('workbuddy', { key: 'ck_0123456789abcdef' })
    assert.deepEqual(list.map(m => m.id), ['glm-5.3', 'kimi-k3', 'custom:team|team'])
    assert.equal(list[0].context, 1000000); assert.equal(list[0].output, 32000); assert.equal(list[0].images, true)
    assert.equal(g.seen.at(-1).headers['x-api-key'], 'ck_0123456789abcdef')
    const before = g.seen.length
    const other = await client.models('workbuddy', { key: 'plain-key-123456' })
    assert.equal(g.seen.length, before, 'a key that is not ck_/pt_ does not ask /v3/config')
    assert.ok(other.length > 3)
    await assert.rejects(client.models('workbuddy'), /sign in first/)
  } finally { await g.close() }
  assert.equal(modelsOfConfig(SITES['workbuddy-ai'], { agents: [{ name: 'cli', models: ['glm-5.3'] }], models: [{ id: 'glm-5.3', supportsImages: true }] })[0].images, false, 'WorkBuddy AI drops images for glm-')
})

test('probe sends the connection check through the signing fetch and hands back status and body', async () => {
  const g = await gateway({ '/v2/chat/completions': (req, b, res) => { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.end('data: {"choices":[{"delta":{"tool_calls":[{"index":0}]}}]}\n\ndata: [DONE]\n\n') } })
  try {
    const client = createClient({ dir: mkdtempSync(join(tmpdir(), 'wb-')), sites: g.sites })
    const r = await client.probe('workbuddy', { key: 'ck_0123456789abcdef', model: 'glm-5.3' })
    assert.equal(r.status, 200)
    assert.match(r.body, /tool_calls/)
    const sent = g.seen.at(-1)
    assert.equal(sent.headers['x-api-key'], 'ck_0123456789abcdef')
    const body = JSON.parse(sent.body)
    assert.equal(body.model, 'glm-5.3'); assert.equal(body.stream, true); assert.equal(body.tools[0].function.name, 'dsh_connection_check')
    assert.equal(body.tool_choice.function.name, 'dsh_connection_check')
    await assert.rejects(client.probe('workbuddy', { key: 'k' }), /requires a model/)
  } finally { await g.close() }
})

// ── several accounts, credits, reasoning ────────────────────────────────────

const builtinHas = site => site.models.some(m => m.efforts !== undefined)
function seedMany(dir, accounts, file = 'workbuddy.json') {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, file), JSON.stringify({ accounts }))
}
const acct = (uid, name, extra = {}) => ({ access: `AT-${uid}`, refresh: `RT-${uid}`, uid, name, domain: 'copilot.tencent.com', expires: Date.now() + 3600e3, refreshExpiresAt: Date.now() + 1e9, ...extra })
const summary = (total, used) => ({ code: 0, data: { IsPaidUser: false, CreditGroups: [{ Kind: 'plan_base', CycleTotalCapacity: String(total), CycleUsedCapacity: String(used) }] } })
const chatPath = '/v2/chat/completions'
const chatAs = (g, client) => client.wrapFetch()(`${g.endpoint}${chatPath}`, { method: 'POST', headers: {}, body: chat() })
const usedBy = g => g.seen.filter(s => s.path === chatPath).map(s => s.headers['x-user-id'])

test('creditsOf reads the plan, the used part and prepaid packages', () => {
  assert.deepEqual(creditsOf({ IsPaidUser: false, CreditGroups: [{ Kind: 'plan_base', CycleTotalCapacity: '500', CycleUsedCapacity: '120.5' }, { Kind: 'addon', CycleTotalCapacity: '100', CycleUsedCapacity: '0' }] }),
    { plan: 'Free', total: 600, used: 120.5, left: 479.5 })
  assert.deepEqual(creditsOf({ IsPaidUser: true, Packages: [{ CycleTotalCapacity: '1000', CycleUsedCapacity: '1000' }, { CycleRemainCapacity: '30' }] }),
    { plan: 'Pro', total: 1000, used: 1000, left: 30 })
  assert.deepEqual(creditsOf({ Packages: [] }), { plan: 'Free', total: null, used: null, left: null })
})

test('accounts: the one with the most credits left serves first, and it stays the one while it can', async () => {
  const left = { 'u-a': summary(500, 450), 'u-b': summary(500, 100) }
  const g = await gateway({
    '/billing/meter/get-user-resource-summary': (req, b, res) => json(res, 200, left[req.headers['x-user-id']]),
    [chatPath]: (req, b, res) => sse(res),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A'), acct('u-b', 'B')])
    const client = createClient({ dir, sites: g.sites })
    const view = await client.usage('workbuddy')
    assert.deepEqual(view.accounts.map(a => [a.name, a.left]), [['A', 50], ['B', 400]])
    await chatAs(g, client); await chatAs(g, client)
    assert.deepEqual(usedBy(g), ['u-b', 'u-b'])
    assert.equal(client.state('workbuddy').accounts.find(a => a.current).name, 'B')
  } finally { await g.close() }
})

test('accounts: a rate-limited account rests and the same chat goes to the next one', async () => {
  const g = await gateway({
    '/billing/meter/get-user-resource-summary': (req, b, res) => json(res, 200, summary(100, 10)),
    [chatPath]: (req, b, res) => (req.headers['x-user-id'] === 'u-a' ? json(res, 429, { code: 429, msg: 'usage exceeds frequency limit' }) : sse(res)),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A'), acct('u-b', 'B')])
    const client = createClient({ dir, sites: g.sites })
    const res = await chatAs(g, client)
    assert.equal(res.status, 200)
    assert.deepEqual(usedBy(g), ['u-a', 'u-b'])
    const a = client.state('workbuddy').accounts.find(x => x.name === 'A')
    assert.equal(a.resting.why, 'rate')
    // the next chat does not try A again
    await chatAs(g, client)
    assert.deepEqual(usedBy(g), ['u-a', 'u-b', 'u-b'])
  } finally { await g.close() }
})

test('accounts: a rested account comes back after its rest; all resting answers with the last refusal', async () => {
  let t = 1_000_000
  const g = await gateway({ [chatPath]: (req, b, res) => json(res, 429, { code: 429, msg: 'usage exceeds frequency limit' }) })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A', { expires: t + 3600e3 })])
    const client = createClient({ dir, sites: g.sites, now: () => t })
    assert.equal((await chatAs(g, client)).status, 429)
    assert.equal(g.seen.filter(s => s.path === chatPath).length, 1)
    // resting: no request goes out, the answer says why
    const again = await chatAs(g, client)
    assert.equal(g.seen.filter(s => s.path === chatPath).length, 1)
    assert.match((await again.json()).error.message, /429|frequency|resting|expired/)
    t += REST_MS.rate + 1
    await chatAs(g, client)
    assert.equal(g.seen.filter(s => s.path === chatPath).length, 2)
  } finally { await g.close() }
})

test('accounts: a restricted account rests a day, an account out of credits an hour; other 4xx are not rotated', async () => {
  assert.equal(turnedAway(403, '{"code":11140}'), 'restricted')
  assert.equal(turnedAway(429, ''), 'rate')
  assert.equal(turnedAway(402, ''), 'credits')
  assert.equal(turnedAway(403, '{"msg":"insufficient credits"}'), 'credits')
  assert.equal(turnedAway(400, '{"code":11128,"msg":"Illegal API invocation from an unapproved channel"}'), undefined)
  assert.equal(turnedAway(500, 'boom'), undefined)
  const g = await gateway({
    '/billing/meter/get-user-resource-summary': (req, b, res) => json(res, 200, summary(100, 0)),
    [chatPath]: (req, b, res) => (req.headers['x-user-id'] === 'u-a' ? json(res, 403, { code: 11140, msg: 'request illegal' }) : sse(res)),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A'), acct('u-b', 'B')])
    const client = createClient({ dir, sites: g.sites })
    assert.equal((await chatAs(g, client)).status, 200)
    assert.equal(client.state('workbuddy').accounts.find(a => a.name === 'A').resting.why, 'restricted')
  } finally { await g.close() }
  const g2 = await gateway({ [chatPath]: (req, b, res) => json(res, 400, { code: 11128, msg: 'Illegal API invocation from an unapproved channel' }) })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A'), acct('u-b', 'B')])
    const res = await chatAs(g2, createClient({ dir, sites: g2.sites }))
    assert.equal(res.status, 400)
    assert.equal(usedBy(g2).length, 1, 'a refused prompt is no account\'s fault: one try')
  } finally { await g2.close() }
})

test('accounts: sign out one account keeps the other; signing in again as the same account replaces it', async () => {
  let n = 0
  const g = await gateway({
    '/v2/plugin/auth/state': (req, b, res) => json(res, 200, { code: 0, data: { state: 'S', authUrl: 'https://login.example/p' } }),
    '/v2/plugin/auth/token': (req, b, res) => json(res, 200, { code: 0, data: { accessToken: `AT-new${++n}`, refreshToken: 'RT-new', expiresIn: 3600 } }),
    '/v2/plugin/login/account': (req, b, res) => json(res, 200, { code: 0, data: { uid: 'u-a', nickname: 'Ada' } }),
  })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    seedMany(dir, [acct('u-a', 'A'), acct('u-b', 'B')])
    const waits = []
    const client = createClient({ dir, sites: g.sites, pollMs: 5, onChange: () => waits.shift()?.() })
    const settled = () => new Promise(r => waits.push(r))
    let w = settled()
    await client.signInStart('workbuddy')
    await w
    const accounts = client.state('workbuddy').accounts
    assert.equal(accounts.length, 2, 'u-a signed in again: replaced, not added')
    assert.equal(client.load(g.sites.workbuddy).find(a => a.uid === 'u-a').access, 'AT-new1')
    client.signOut('workbuddy', 'u-b|')
    assert.deepEqual(client.state('workbuddy').accounts.map(a => a.id), ['u-a|'])
    client.signOut('workbuddy')
    assert.equal(client.state('workbuddy').signedIn, false)
  } finally { await g.close() }
})

test('reasoning: levels come from the config, none is a level when the model can be turned off, the default is the model\'s own', () => {
  const site = SITES.workbuddy
  const cfg = { agents: [{ name: 'cli', models: ['a', 'b', 'c', 'd'] }], models: [
    { id: 'a', supportsReasoning: true, reasoning: { supportedEfforts: ['low', 'high', 'max'], defaultEffort: 'high' } },
    { id: 'b', supportsReasoning: true, onlyReasoning: true, reasoning: { supportedEfforts: ['high', 'xhigh'], effort: 'high' } },
    { id: 'c', supportsReasoning: true, reasoning: { effort: 'medium' } },
    { id: 'd' },
  ] }
  const [a, b, c, d] = modelsOfConfig(site, cfg)
  assert.deepEqual(a.efforts, ['none', 'low', 'high', 'max']); assert.equal(a.effort, 'high')
  assert.deepEqual(b.efforts, ['high', 'xhigh'], 'a model that only reasons has no off level')
  assert.deepEqual(c.efforts, ['none', 'low', 'medium', 'high', 'max']); assert.equal(c.effort, 'medium')
  assert.deepEqual(d.efforts, [], 'a model without reasoning: said so, not left unknown')
  assert.equal(builtinHas(site), false)
  assert.deepEqual(effortsOf({ supportsReasoning: false }), [])
  assert.deepEqual(modelsOfConfig(site, { agents: [{ name: 'cli', models: ['deepseek-v4.1-flash'] }], models: [{ id: 'deepseek-v4.1-flash', supportsReasoning: true, onlyReasoning: true, reasoning: { supportedEfforts: ['low', 'high'] } }] })[0].efforts, ['none', 'low', 'high'])
})

test('reasoning: minimal asks for off, a chat with no level gets the model\'s default unless its budget is small', () => {
  const info = { efforts: ['none', 'low', 'high'], effort: 'high' }
  const off = { reasoning_effort: 'minimal', max_tokens: 4000 }
  assert.equal(applyEffort(off, info), true); assert.equal(off.reasoning_effort, 'none')
  const dflt = { max_tokens: 4000 }
  assert.equal(applyEffort(dflt, info), true); assert.equal(dflt.reasoning_effort, 'high')
  const small = { max_tokens: 64 }
  assert.equal(applyEffort(small, info), false); assert.equal(small.reasoning_effort, undefined)
  const named = { reasoning_effort: 'low' }
  assert.equal(applyEffort(named, info), false); assert.equal(named.reasoning_effort, 'low')
  assert.equal(applyEffort({ max_tokens: 4000 }, undefined), false)
  assert.equal(applyEffort({ reasoning_effort: 'minimal' }, { efforts: ['low', 'high'], effort: 'high' }), false, 'no off level: left as asked')
})

test('reasoning: a chat is sent with the level the last config read gave its model', async () => {
  const cfg = { code: 0, data: { agents: [{ name: 'cli', models: ['glm-5.3'] }], models: [{ id: 'glm-5.3', supportsReasoning: true, reasoning: { supportedEfforts: ['low', 'high'], defaultEffort: 'high' } }] } }
  const g = await gateway({ '/v3/config': (req, b, res) => json(res, 200, cfg), [chatPath]: (req, b, res) => sse(res) })
  try {
    const dir = mkdtempSync(join(tmpdir(), 'wb-'))
    const client = createClient({ dir, sites: g.sites })
    const f = client.wrapFetch()
    const send = (extra) => f(`${g.endpoint}${chatPath}`, { method: 'POST', headers: { authorization: 'Bearer ck_0123456789abcdef' }, body: chat({ max_tokens: 4000, ...extra }) })
    await send({})
    assert.equal(JSON.parse(g.seen.at(-1).body).reasoning_effort, undefined, 'before any config read the chat goes as it is')
    await client.models('workbuddy', { key: 'ck_0123456789abcdef' })
    await send({})
    assert.equal(JSON.parse(g.seen.at(-1).body).reasoning_effort, 'high')
    await send({ reasoning_effort: 'minimal' })
    assert.equal(JSON.parse(g.seen.at(-1).body).reasoning_effort, 'none')
  } finally { await g.close() }
})
