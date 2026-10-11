// Tests the real hook and Git range, including an invalid non-HEAD commit.
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { copyFileSync, mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import test from 'node:test'
import load from '@commitlint/load'
import lint from '@commitlint/lint'
import { checkCommits, PROJECT_ROOT } from '../check_commits.mjs'

const config = await load({}, { cwd: PROJECT_ROOT })

for (const [message, valid] of [
  ['feat(system): add company membership commands', true],
  ['fix(auth/session): 拒绝已撤销的会话', true],
  ['deps(bom): update dependency versions', true],
  ['wip(tooling): establish a review baseline', true],
  ['feat: missing scope', false],
  ['fix(): empty scope', false],
  ['ci(tooling): type absent from Admin whitelist', false],
  ['docs(readme): ', false],
  ['v1.0.0', false],
  ['Merge branch \'dev\' into feat/example', true],
]) {
  test(`commit message: ${message}`, async () => {
    const result = await lint(message, config.rules, {
      parserOpts: config.parserPreset?.parserOpts,
      defaultIgnores: config.defaultIgnores, ignores: config.ignores,
    })
    assert.equal(result.valid, valid, JSON.stringify(result.errors))
  })
}

function fixture(t) {
  const cwd = mkdtempSync(resolve(tmpdir(), 'robot commit hook '))
  t.after(() => rmSync(cwd, { recursive: true, force: true }))
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', env: { ...process.env, HUSKY: '1' } }).trim()
  git('init', '--quiet')
  git('config', 'user.name', 'Tooling Test')
  git('config', 'user.email', 'tooling@example.invalid')
  git('config', 'commit.gpgsign', 'false')
  git('config', 'core.hooksPath', '/dev/null')
  git('commit', '--quiet', '--allow-empty', '-m', 'feat: already published legacy commit')
  const baseline = git('rev-parse', 'HEAD')
  symlinkSync(resolve(PROJECT_ROOT, 'node_modules'), resolve(cwd, 'node_modules'), 'dir')
  copyFileSync(resolve(PROJECT_ROOT, 'commitlint.config.cjs'), resolve(cwd, 'commitlint.config.cjs'))
  writeFileSync(resolve(cwd, 'package.json'), JSON.stringify({ private: true, scripts: { commitlint: 'commitlint' } }))
  mkdirSync(resolve(cwd, '.husky'))
  copyFileSync(resolve(PROJECT_ROOT, '.husky/commit-msg'), resolve(cwd, '.husky/commit-msg'))
  execFileSync(process.execPath, [resolve(PROJECT_ROOT, 'node_modules/husky/bin.js')], { cwd })
  return { cwd, git, baseline }
}

test('real Git hook rejects missing scope, preserves HEAD, and accepts a scoped commit', (t) => {
  const { cwd, git, baseline } = fixture(t)
  const rejected = spawnSync('git', ['commit', '--allow-empty', '-m', 'feat: should be rejected'], {
    cwd, encoding: 'utf8', env: { ...process.env, HUSKY: '1' },
  })
  assert.notEqual(rejected.status, 0)
  assert.match(rejected.stdout + rejected.stderr, /scope-empty/)
  assert.equal(git('rev-parse', 'HEAD'), baseline)
  git('commit', '--quiet', '--allow-empty', '-m', 'feat(system): accepted command')
  assert.notEqual(git('rev-parse', 'HEAD'), baseline)
})

test('range gate rejects an invalid intermediate commit even if HEAD is valid', async (t) => {
  const { cwd, git, baseline } = fixture(t)
  // Deliberate hook bypass verifies that CI still detects the bad commit.
  git('-c', 'core.hooksPath=/dev/null', 'commit', '--quiet', '--allow-empty', '-m', 'fix: bypassed local hook')
  git('commit', '--quiet', '--allow-empty', '-m', 'test(tooling): valid head')
  const result = await checkCommits({ cwd, historicalHead: baseline })
  assert.equal(result.checked, 2)
  assert.equal(result.ok, false)
  assert.equal(result.commits.filter((commit) => !commit.valid).length, 1)
})

test('range gate accepts scoped commits after the legacy adoption boundary', async (t) => {
  const { cwd, git, baseline } = fixture(t)
  git('commit', '--quiet', '--allow-empty', '-m', 'docs(readme): document backend tooling')
  const result = await checkCommits({ cwd, historicalHead: baseline })
  assert.equal(result.ok, true)
  assert.equal(result.checked, 1)
})

test('unknown adoption commit and unsafe range fail closed', async (t) => {
  const { cwd, baseline } = fixture(t)
  await assert.rejects(checkCommits({ cwd, historicalHead: 'f'.repeat(40) }))
  await assert.rejects(checkCommits({ cwd, historicalHead: baseline, range: '--all' }), /invalid Git range/)
})
