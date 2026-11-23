import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import vm from 'node:vm'

const source = readFileSync(join(process.env.DSH_STAGE_NM, '@deepseek-ai/dsh-attachment-local/lib/index.js'), 'utf8')
const start = source.indexOf('async function syncDirectory(path) {')
const end = source.indexOf('\n}\n', start) + 2
assert.ok(start >= 0 && end > start)
const syncSource = source.slice(start, end)

function harness(platform, failure) {
  const calls = []
  const sync = vm.runInNewContext(`(${syncSource})`, {
    process: { platform, getuid: () => 0 },
    constants: { O_RDONLY: 0 },
    open: async path => {
      calls.push(['open', path])
      return {
        sync: async () => { calls.push(['sync', path]); if (failure) throw failure },
        close: async () => { calls.push(['close', path]) },
      }
    },
  })
  return { sync, calls }
}

test('Root on Android never attempts fsync on system-owned ancestors', async () => {
  const { sync, calls } = harness('android', Object.assign(new Error('unsupported system root'), { code: 'EINVAL' }))
  for (const path of ['/', '/data', '/data/data', '/data/user', '/data/user/0', '/data/user/10', '/data/user_de', '/data/user_de/0']) await sync(path)
  assert.deepEqual(calls, [])
})

test('App sandbox and attachment entries retain their durability checks', async () => {
  const { sync, calls } = harness('android')
  for (const path of ['/data/user/0/com.dsh.android', '/data/user/0/com.dsh.android/files/dsh-home/attachments/v1', '/data/data/com.dsh.android']) await sync(path)
  assert.equal(calls.filter(([op]) => op === 'sync').length, 3)
  assert.equal(calls.filter(([op]) => op === 'close').length, 3)
})

test('Real App storage errors are reported and handles are closed', async () => {
  const failure = Object.assign(new Error('storage error'), { code: 'EIO' })
  const { sync, calls } = harness('android', failure)
  await assert.rejects(sync('/data/user/0/com.dsh.android/files'), error => error === failure)
  assert.equal(calls.at(-1)[0], 'close')
})

test('Other POSIX systems keep the original root fsync behavior', async () => {
  const { sync, calls } = harness('linux')
  await sync('/')
  assert.deepEqual(calls.map(([op]) => op), ['open', 'sync', 'close'])
})
