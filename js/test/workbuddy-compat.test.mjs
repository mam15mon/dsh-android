// Contracts adapted from magpie-community/plugins WorkBuddy compatibility, no live accounts.
import test from 'node:test'
import assert from 'node:assert/strict'
import { createChatCompatibility, explainResponse } from '../packages/plugin-workbuddy/compatibility.js'

const request = () => ({ model: 'deepseek-v4.1-flash', messages: [
  { role: 'system', content: 'You are an AI assistant running in an Android app.' },
  { role: 'user', content: 'My original input.' },
] })
const refusal = () => new Response(JSON.stringify({ code: 11128, msg: 'Illegal API invocation from an unapproved channel' }), { status: 400 })

test('channel retries match upstream order and remember the successful prompt adaptation', async () => {
  const compatibility = createChatCompatibility()
  const seen = []
  const send = async body => {
    const b = JSON.parse(body); seen.push(b)
    return b.messages[0].content === 'You are a helpful assistant.' ? new Response('OK') : refusal()
  }
  assert.equal((await compatibility.send(compatibility.prepare(request(), 'cn'), send)).status, 200)
  assert.equal(seen.length, 3, 'original, comma-paused opening, instructions in the user message')
  assert.match(seen[1].messages[0].content, /assistant, running in/)
  assert.match(seen[2].messages[1].content, /^<instructions>\n/)
  assert.ok(seen[2].messages[1].content.endsWith('My original input.'))
  seen.length = 0
  await compatibility.send(compatibility.prepare(request(), 'cn'), send)
  assert.equal(seen.length, 1, 'same prompt on the same route uses its known adaptation')
  seen.length = 0
  await compatibility.send(compatibility.prepare(request(), 'international'), send)
  assert.equal(seen.length, 3, 'another site must not inherit channel decisions')
})

test('an unrelated 400, 401 or 500 is not retried or rewritten as a channel refusal', async () => {
  for (const status of [400, 401, 500]) {
    const compatibility = createChatCompatibility(); let attempts = 0
    const res = await compatibility.send(compatibility.prepare(request(), 'cn'), async () => {
      attempts++; return new Response('unrelated failure', { status })
    })
    assert.equal(attempts, 1); assert.equal(res.status, status)
    assert.equal(await res.text(), 'unrelated failure')
  }
})

test('a permanently refused prompt has a bounded retry budget', async () => {
  const compatibility = createChatCompatibility(); let attempts = 0
  const res = await compatibility.send(compatibility.prepare(request(), 'cn'), async () => { attempts++; return refusal() })
  assert.equal(attempts, 3)
  assert.equal(res.status, 400)
  assert.equal((await res.json()).code, 11128)
})

test('tool schemas and names round trip through fragmented SSE with no name collisions', async () => {
  const compatibility = createChatCompatibility()
  const chat = request()
  chat.tools = ['mcp.tool', 'mcp_tool'].map(name => ({ type: 'function', function: { name, parameters: {
    type: 'object', properties: { path: { type: 'string', pattern: '^[^\\0]+$' }, mode: { enum: ['a', 'b'] }, tuple: { type: 'array', items: [{ type: 'string' }, { type: 'integer' }] } },
  } } }))
  chat.tool_choice = { type: 'function', function: { name: 'mcp.tool' } }
  chat.messages.push({ role: 'assistant', content: null, tool_calls: [{ id: 'c1', type: 'function', function: { name: 'mcp.tool', arguments: '{}' } }] }, { role: 'tool', tool_call_id: 'c1', content: 'ok' })
  const prepared = compatibility.prepare(chat, 'cn')
  const sent = JSON.parse(prepared.body)
  assert.deepEqual(sent.tools.map(t => t.function.name), ['mcp_tool_2', 'mcp_tool'])
  assert.equal(sent.tool_choice, 'mcp_tool_2')
  assert.equal(sent.messages[2].tool_calls[0].function.name, 'mcp_tool_2')
  const schema = sent.tools[0].function.parameters
  assert.equal(schema.properties.path.pattern, '^[^\\u0000]+$')
  assert.deepEqual(schema.properties.mode, { type: 'string', enum: ['a', 'b'] })
  assert.deepEqual(schema.properties.tuple.items, { anyOf: [{ type: 'string' }, { type: 'integer' }] })
  const wire = 'data: '+JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: { name: 'mcp_tool_2', arguments: '{}' } }] } }] })+'\n\ndata: [DONE]\n\n'
  const enc = new TextEncoder()
  const res = await compatibility.send(prepared, async () => new Response(new ReadableStream({ start(c) {
    for (let i = 0; i < wire.length; i += 7) c.enqueue(enc.encode(wire.slice(i, i + 7)))
    c.close()
  } }), { headers: { 'content-type': 'text/event-stream' } }))
  const output = await res.text()
  assert.match(output, /"name":"mcp.tool"/)
  assert.ok(output.endsWith('data: [DONE]\n\n'))
})

test('tool-results followed by a user message retry with the upstream empty assistant separator', async () => {
  const compatibility = createChatCompatibility(); const seen = []
  const chat = request()
  chat.messages.push({ role: 'tool', tool_call_id: 'c1', content: 'done' }, { role: 'user', content: 'Continue' })
  const res = await compatibility.send(compatibility.prepare(chat, 'cn'), async body => {
    const b=JSON.parse(body); seen.push(b)
    return seen.length === 1 ? new Response('{"code":11153,"msg":"must end with a user message"}', {status:400}) : new Response('OK')
  })
  assert.equal(res.status,200)
  assert.deepEqual(seen[1].messages.slice(-3), [{ role: 'tool', tool_call_id: 'c1', content: 'done' }, {role:'assistant',content:''}, {role:'user',content:'Continue'}])
})

test('Tencent msg/code/request id survive normalization into adapter-readable errors', async () => {
  const raw = {code:11128,msg:'Illegal API invocation from an unapproved channel',requestId:'req-123'}
  const res = await explainResponse(new Response(JSON.stringify(raw),{status:400,headers:{'content-type':'application/json','content-length':'1'}}))
  const b=await res.json()
  assert.equal(b.code,11128)
  assert.equal(b.error.code,'11128')
  assert.match(b.error.message,/unapproved channel.*11128/)
  assert.equal(b.requestId,'req-123')
  assert.equal(res.headers.get('content-length'),null)
})
