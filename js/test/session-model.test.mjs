import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { join } from 'node:path'

const { METHOD, normalizeSelection, setupSessionModel } = await import(pathToFileURL(join(process.env.DSH_STAGE_NM, '@dsh-android/plugin-guard/session-model.js')))

function harness() {
  const events = new Map()
  const agents = new Map()
  const selected = []
  const ctx = {
    on(name, listener) { events.set(name, [...events.get(name) ?? [], listener]) },
    get(name) {
      if (name === 'agents') return { get: id => agents.get(id), list: () => [...agents.values()] }
      if (name === 'llm') return { async resolveCallConfig(c) {
        if (!['default', 'other'].includes(c.provider)) throw new Error('unknown provider')
        if (c.reasoningEffort && !['low', 'high'].includes(c.reasoningEffort)) throw new Error('unsupported effort')
        return c
      } }
    },
  }
  setupSessionModel(ctx, { installNotice() {} })
  const call = params => events.get('sdk-extensions/resolve')[0](METHOD)(params)
  async function create(id, parent) {
    const agent = { id, session: { id, header: parent ? { parentSession: parent } : {} }, options: { provider: 'default', model: 'base', reasoningEffort: 'low' }, model: { select(value) { selected.push({ id, value }) } } }
    agents.set(id, agent)
    for (const listener of events.get('agent/created') ?? []) await listener({ agent })
    return agent
  }
  return { call, create, selected }
}

test('invalid selections are refused before routing', () => {
  assert.throws(() => normalizeSelection({ sessionId: 's', provider: '', model: 'x' }), TypeError)
  assert.throws(() => normalizeSelection({ provider: 'other', model: 'x' }), TypeError)
})

test('a selection saved before creation reaches Agent.model.select', async () => {
  const h = harness()
  await h.call({ sessionId: 's', provider: 'other', model: 'x', reasoningEffort: 'high' })
  assert.equal(h.selected.length, 0)
  await h.create('s')
  assert.deepEqual(h.selected, [{ id: 's', value: { provider: 'other', model: 'x', reasoningEffort: 'high' } }])
})

test('live changes use the new model API; null restores the creation route', async () => {
  const h = harness()
  await h.create('s')
  await h.call({ sessionId: 's', provider: 'other', model: 'x' })
  await h.call({ sessionId: 's', model: null })
  assert.deepEqual(h.selected.map(x => x.value), [{ provider: 'other', model: 'x' }, { provider: 'default', model: 'base', reasoningEffort: 'low' }])
  await h.create('s')
  assert.equal(h.selected.length, 2, 'forgotten selection is not reinstalled')
})

test('unknown routes and unsupported effort leave the previous selection intact', async () => {
  const h = harness()
  await h.create('s')
  await h.call({ sessionId: 's', provider: 'other', model: 'x' })
  await assert.rejects(h.call({ sessionId: 's', provider: 'gone', model: 'x' }), /unknown provider/)
  await assert.rejects(h.call({ sessionId: 's', provider: 'other', model: 'x', reasoningEffort: 'max' }), /unsupported effort/)
  assert.equal(h.selected.length, 1)
})

test('another conversation and a child agent are not assigned the root selection', async () => {
  const h = harness()
  await h.call({ sessionId: 's', provider: 'other', model: 'x' })
  await h.create('s')
  await h.create('background')
  await h.create('s/child', 's')
  assert.deepEqual(h.selected.map(x => x.id), ['s'])
})
