// Patch 28 (llm-service-lite: the output-cap field an endpoint takes, learned from its refusal) end to
// end: the real OwcProfilesAdapter against synthetic node:http servers. Runs against a built stage tree:
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-output-cap.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const lite = await import(pathToFileURL(join(NM, '@snnh/dsh-llm-service-lite/lib/index.js')).href)
const { OwcProfilesAdapter, ConcurrencyLimiter, resolveProfiles } = lite

/** A server that takes only `accepts` as the output-cap field and refuses the other with `refusal`. */
async function serve(accepts, refusal) {
  const bodies = []
  const server = createServer((req, res) => {
    let raw = ''
    req.on('data', c => { raw += c })
    req.on('end', () => {
      const body = JSON.parse(raw)
      bodies.push(body)
      const sent = ['max_tokens', 'max_completion_tokens'].filter(k => body[k] !== undefined)
      if (sent.some(k => k !== accepts)) {
        res.writeHead(400, { 'content-type': 'application/json' })
        return res.end(JSON.stringify(refusal(sent.find(k => k !== accepts))))
      }
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      res.write(`data: ${JSON.stringify({ choices: [{ index: 0, delta: { content: 'ok' }, finish_reason: 'stop' }] })}\n\n`)
      res.end('data: [DONE]\n\n')
    })
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  return { bodies, origin: `http://127.0.0.1:${server.address().port}`, close: () => new Promise(r => server.close(r)) }
}

async function turn(baseURL) {
  const profiles = resolveProfiles({ t: { interfaceType: 'openai-chat-completions', baseURL, apiKey: 'sk-test', models: [{ id: 'm' }] } })
  const limiter = new ConcurrencyLimiter(1)
  const adapter = new OwcProfilesAdapter({ profiles: () => profiles, limiter: () => limiter, resolveApiKey: async p => p.apiKey })
  const chunks = []
  try {
    for await (const c of adapter.stream({ provider: 't', model: 'm', maxTokens: 100, messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }] }] })) chunks.push(c)
    return { chunks }
  } catch (error) { return { error, chunks } }
}
const text = chunks => chunks.filter(c => c.type === 'text-delta').map(c => c.text).join('')

test('OpenAI-style refusal of max_tokens: asked again with max_completion_tokens, then remembered', async () => {
  const s = await serve('max_completion_tokens', () => ({ error: { message: "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.", type: 'invalid_request_error', param: 'max_tokens', code: 'unsupported_parameter' } }))
  try {
    const first = await turn(`${s.origin}/v1`)
    assert.equal(first.error, undefined, first.error?.stack)
    assert.equal(text(first.chunks), 'ok')
    assert.equal(s.bodies.length, 2)
    assert.equal(s.bodies[0].max_tokens, 100)
    assert.equal(s.bodies[1].max_completion_tokens, 100)
    assert.equal(s.bodies[1].max_tokens, undefined)
    // a later turn on the same endpoint goes straight with the learned spelling
    const second = await turn(`${s.origin}/v1`)
    assert.equal(text(second.chunks), 'ok')
    assert.equal(s.bodies.length, 3)
    assert.equal(s.bodies[2].max_completion_tokens, 100)
  } finally { await s.close() }
})

test('a relay that words the refusal its own way is still understood', async () => {
  const s = await serve('max_completion_tokens', () => ({ message: 'upstream 400: max_tokens is not supported, please use max_completion_tokens' }))
  try {
    const r = await turn(`${s.origin}/v1`)
    assert.equal(r.error, undefined, r.error?.stack)
    assert.equal(s.bodies.length, 2)
  } finally { await s.close() }
})

test('an endpoint that knows only max_tokens is never touched', async () => {
  const s = await serve('max_tokens', () => ({ error: { message: 'unknown field' } }))
  try {
    const r = await turn(`${s.origin}/v1`)
    assert.equal(text(r.chunks), 'ok')
    assert.equal(s.bodies.length, 1)
    assert.equal(s.bodies[0].max_tokens, 100)
  } finally { await s.close() }
})

test('an unrelated 400 is not retried and reaches the user as it was', async () => {
  const s = await serve('none', () => ({ error: { message: 'messages: at least one message is required' } }))
  try {
    const r = await turn(`${s.origin}/v1`)
    assert.ok(r.error)
    assert.match(r.error.message, /at least one message/)
    assert.equal(s.bodies.length, 1)
  } finally { await s.close() }
})
