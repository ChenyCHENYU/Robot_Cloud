// Verifies profile exclusions and that the actual BD gate blocks new debt.
import assert from 'node:assert/strict'
import { copyFileSync, mkdtempSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'
import test from 'node:test'
import { executeBd, PROJECT_ROOT, ROBOT_RULES } from '../check_backend_rules.mjs'

const require = createRequire(import.meta.url)
const { resolveProfile } = require('@agile-team/wl-skills-bd/lib/contract.js')

test('all installed codegen profiles are disabled for Robot', () => {
  const profiles = readdirSync(resolve(PROJECT_ROOT, '.wl-skills-bd/profiles')).filter((file) => file.endsWith('.json'))
  assert.ok(profiles.includes('robot-boot3-audit.json'))
  for (const file of profiles) {
    assert.throws(() => resolveProfile(file.slice(0, -5), PROJECT_ROOT), /不允许代码生成/)
  }
})

test('native BD scans every configured generic rule and rejects a new cache without TTL', (t) => {
  const cwd = mkdtempSync(resolve(tmpdir(), 'robot bd gate '))
  t.after(() => rmSync(cwd, { recursive: true, force: true }))
  mkdirSync(resolve(cwd, '.wl-skills-bd'))
  for (const file of ['quality-gate.json', 'review-baseline.json']) {
    copyFileSync(resolve(PROJECT_ROOT, '.wl-skills-bd', file), resolve(cwd, '.wl-skills-bd', file))
  }
  const sourceRoot = resolve(cwd, 'src/main/java/com/robot/probe')
  mkdirSync(sourceRoot, { recursive: true })
  const args = ['review', 'run', '--rules', ROBOT_RULES.join(','), '--limit', '500']
  const clean = executeBd(args, { cwd })
  assert.equal(clean.exitCode, 0, JSON.stringify(clean.report))
  assert.deepEqual(clean.report.ruleCoverage.evaluatedRules, ROBOT_RULES)
  writeFileSync(resolve(sourceRoot, 'UnsafeCache.java'), `
package com.robot.probe;
import org.springframework.data.redis.core.RedisTemplate;
public class UnsafeCache {
    private RedisTemplate<String, String> redisTemplate;
    public void cache() {
        redisTemplate.opsForValue().set("probe", "value");
    }
}
`)
  const unsafe = executeBd(args, { cwd })
  assert.equal(unsafe.exitCode, 1)
  assert.equal(unsafe.report.ok, false)
  assert.ok(unsafe.report.blockers.some((finding) => finding.rule === 'B13'), JSON.stringify(unsafe.report))
})
