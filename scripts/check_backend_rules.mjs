// Keep full native evidence; gate the explicitly supported Robot rule subset.
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const PROJECT_ROOT = fileURLToPath(new URL('../', import.meta.url))
export const ROBOT_RULES = Array.from({ length: 31 }, (_, index) => `B${index + 2}`)
const cli = resolve(PROJECT_ROOT, 'node_modules/@agile-team/wl-skills-bd/bin/wl-skills-bd.js')

export function executeBd(args, { cwd = PROJECT_ROOT, runId = process.env.WL_TASK_RUN_ID } = {}) {
  const result = spawnSync(process.execPath, [cli, ...args, '--target', cwd,
    ...(runId ? ['--run-id', runId] : []), '--json'], { cwd, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 })
  if (result.error) throw result.error
  let report
  try { report = JSON.parse(result.stdout) }
  catch { throw new Error(`BD did not return JSON: ${result.stderr || result.stdout}`) }
  return { exitCode: result.status, report }
}

export function main(args = process.argv.slice(2)) {
  if (args.length) throw new Error('usage: bun run check:bd')
  const output = resolve(PROJECT_ROOT, 'target/verification')
  mkdirSync(output, { recursive: true })
  // Native full scan may fail on incompatible B1; retain it without calling it a pass.
  const native = executeBd(['validate'])
  writeFileSync(resolve(output, 'bd-native.json'), JSON.stringify(native, null, 2) + '\n')
  if (![0, 1].includes(native.exitCode) || native.report.coverage?.status !== 'complete') {
    throw new Error('full native BD scan failed or returned incomplete coverage')
  }
  const review = executeBd(['review', 'run', '--rules', ROBOT_RULES.join(','), '--limit', '500'])
  writeFileSync(resolve(output, 'bd-robot-review.json'), JSON.stringify(review, null, 2) + '\n')
  const evaluated = review.report.ruleCoverage?.evaluatedRules ?? []
  if (evaluated.length !== ROBOT_RULES.length || ROBOT_RULES.some((rule) => !evaluated.includes(rule))) {
    throw new Error('Robot BD gate did not evaluate all 31 configured rules')
  }
  console.log(JSON.stringify({
    native: { exitCode: native.exitCode, stats: native.report.stats, coverage: native.report.coverage },
    robot: { ok: review.report.ok, summary: review.report.qualityGate?.summary, coverage: review.report.ruleCoverage },
    permissionAdapter: 'B1 needs Sa-Token adaptation; Robot authorization behavior tests remain required',
    reports: ['target/verification/bd-native.json', 'target/verification/bd-robot-review.json'],
  }, null, 2))
  return review.exitCode === 0 && review.report.ok ? 0 : 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = main() }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
