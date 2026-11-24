// plugin-workbuddy inside the real engine (docs/ENGINE-CONTRACT.md §2.8): the plugin mounts from the app's profile,
// serves its SDK methods, and a signed-out chat or probe is refused before any request leaves. Runs against a built
// stage tree (the real engine boots, ~2 s):
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/workbuddy-engine.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdirSync, mkdtempSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const NATIVE = resolve(process.env.DSH_NATIVE_DIR ?? resolve(NM, '../../host-native'))

/** Newline-delimited JSON-RPC over a stream pair (client side; server requests get [onRequest]'s answer). */
function rpcPeer(readable, writable, onRequest) {
  let buf = ''
  let id = 0
  const pending = new Map()
  readable.setEncoding('utf8')
  readable.on('data', d => {
    buf += d
    let n
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n); buf = buf.slice(n + 1)
      let m
      try { m = JSON.parse(line) } catch { continue }
      if (m.method !== undefined && m.id !== undefined) {
        Promise.resolve().then(() => onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method === undefined) {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return { request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }) }
}

test('engine: the workbuddy/* methods answer, a signed-out probe is refused locally, sign-out is a no-op', { timeout: 120_000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'wb-engine-'))
  const dshHome = join(root, 'dsh-home'), home = join(root, 'home'), tmp = join(root, 'tmp')
  for (const d of [join(dshHome, 'android'), home, tmp]) mkdirSync(d, { recursive: true })
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_BRIDGE_FD: '3',
  }
  const child = spawn(process.execPath, ['--expose-internals', '--require', join(NM, '@dsh-android/bundle-android/android-env.cjs'), join(NM, '@dsh-android/bundle-android/main.mjs')],
    { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  let stderr = ''
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], method => {
    if (method === 'capability/list') return { tools: [] }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  const sdk = rpcPeer(child.stdout, child.stdin, () => ({ outcome: 'allowed-once' }))
  const until = (what, ms = 60_000) => Promise.race([what, sleep(ms, undefined, { ref: false }).then(() => { throw new Error(`timeout\n${stderr.slice(-2000)}`) })])
  try {
    const state = await until(sdk.request('workbuddy/state', { site: 'workbuddy' }))
    assert.deepEqual([state.site, state.signedIn, state.accounts, state.attempt], ['workbuddy', false, [], null])
    // an unknown build is refused, not guessed
    await assert.rejects(until(sdk.request('workbuddy/state', { site: 'nope' })), /site must be one of/)
    // a key that is not ck_/pt_ lists the build's known models without asking the network
    const { models } = await until(sdk.request('workbuddy/models', { site: 'workbuddy-ai', key: 'plain-key-123456' }))
    assert.ok(models.length > 3 && models.every(m => typeof m.id === 'string'))
    // signed out: the probe is refused by the plugin itself (its 401 says to sign in), nothing is sent
    const probe = await until(sdk.request('workbuddy/probe', { site: 'workbuddy', model: 'glm-5.3' }))
    assert.equal(probe.status, 401)
    assert.match(probe.body, /sign in/i)
    const out = await until(sdk.request('workbuddy/sign-out', { site: 'workbuddy' }))
    assert.equal(out.signedIn, false)
    assert.equal(existsSync(join(dshHome, 'android/workbuddy/workbuddy.json')), false)
  } finally {
    await Promise.race([sdk.request('shutdown', {}).catch(() => {}), sleep(5000)])
    child.kill('SIGKILL')
    for (const s of child.stdio) s?.destroy()
  }
})
