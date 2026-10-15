// Keep full native evidence; gate the explicitly supported Robot rule subset.
import { spawnSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { evaluateRobot, inputDigest, inputInventory, readInputs } from './tooling/robot_policy.mjs'
import { deriveSemanticReview, evaluateSemanticGate, sha256 } from './tooling/backend_semantic_review.mjs'

export const PROJECT_ROOT = fileURLToPath(new URL('../', import.meta.url))
export const ROBOT_RULES = Array.from({ length: 31 }, (_, index) => `B${index + 2}`)
const cli = resolve(PROJECT_ROOT, 'node_modules/@agile-team/wl-skills-bd/bin/wl-skills-bd.js')
const require = createRequire(import.meta.url)
const runtime = require('@agile-team/wl-skills-bd/lib/task-runtime.js')
const { runBeRules } = require('@agile-team/wl-skills-bd/lib/be-rules.js')
const { runReview } = require('@agile-team/wl-skills-bd/lib/review.js')
const { evaluateQualityGate, loadQualityGate, loadBaseline } = require('@agile-team/wl-skills-bd/lib/quality-gate.js')

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
  // The CLI snapshots '.' including target outputs. Use the package's real executors
  // with an explicit inventory, and retain every native finding without rewriting it.
  const inventory = inputInventory(PROJECT_ROOT)
  const beforeDigest = inputDigest(PROJECT_ROOT, inventory.files)
  const runId = process.env.WL_TASK_RUN_ID
  const options = { runId, targets: inventory.files }
  const nativeHandle = runtime.validationStart(PROJECT_ROOT, options)
  const nativeReport = runBeRules(PROJECT_ROOT)
  const native = { exitCode: nativeReport.stats.error ? 1 : 0, report: nativeReport }
  native.receipt = runtime.validationFinish(nativeHandle, native.report, native.exitCode)
  writeFileSync(resolve(output, 'bd-native.json'), JSON.stringify(native, null, 2) + '\n')
  if (![0, 1].includes(native.exitCode) || native.report.coverage?.status !== 'complete') {
    throw new Error('full native BD scan failed or returned incomplete coverage')
  }
  const reviewHandle = runtime.observation.beginExecution(runtime.options(PROJECT_ROOT,
    { ...options, tool: 'backend-review', readOnlyVerification: true }))
  const reviewReport = runReview(PROJECT_ROOT, { rules: ROBOT_RULES, limit: 500 })
  const review = { exitCode: reviewReport.ok ? 0 : 1, report: reviewReport,
    receipt: runtime.reviewFinish(reviewHandle, reviewReport) }
  writeFileSync(resolve(output, 'bd-robot-review.json'), JSON.stringify(review, null, 2) + '\n')
  const evaluated = review.report.ruleCoverage?.evaluatedRules ?? []
  if (evaluated.length !== ROBOT_RULES.length || ROBOT_RULES.some((rule) => !evaluated.includes(rule))) {
    throw new Error('Robot BD gate did not evaluate all 31 configured rules')
  }
  const semanticHandle = runtime.observation.beginExecution(runtime.options(PROJECT_ROOT,
    { ...options, tool: 'robot-bd-semantic-review', readOnlyVerification: true,
      ruleFiles: ['scripts/tooling/backend_semantic_review.mjs', 'scripts/tooling/java_field_review.mjs'],
      configFiles: ['quality/backend-semantic-reviews.json', '.wl-skills-bd/quality-gate.json', '.wl-skills-bd/review-baseline.json'] }))
  let semantic
  try {
    const policy = JSON.parse(readFileSync(resolve(PROJECT_ROOT, 'quality/backend-semantic-reviews.json'), 'utf8'))
    semantic = deriveSemanticReview(PROJECT_ROOT, native.report, review.report, policy)
    const loaded = loadQualityGate(PROJECT_ROOT)
    const baseline = loadBaseline(PROJECT_ROOT, loaded.config)
    // Reuse the package's real gate/budget/baseline. Unknown original reasons must not disappear.
    semantic.effectiveGate = evaluateSemanticGate(review.report, semantic,
      { config: loaded.config, configDiagnostics: loaded.diagnostics, baseline }, evaluateQualityGate)
    semantic.ok = semantic.effectiveGate.ok
  } catch (error) {
    semantic = { schemaVersion: 1, ok: false, error: error.message,
      mandatoryBlockers: [{ rule: 'ROBOT_SEMANTIC_REVIEW', message: error.message }] }
  }
  semantic.originalReports = ['bd-native.json', 'bd-robot-review.json'].map((file) => ({
    path: `target/verification/${file}`, sha256: sha256(readFileSync(resolve(output, file))),
  }))
  const semanticChecks = [{ id: 'RBD_EXACT_NATIVE_SEMANTIC_REVIEW', status: semantic.ok ? 'pass' : 'fail' }]
  const semanticFiles = new Set((semantic.reviewClassifications ?? [])
    .filter((finding) => finding.disposition !== 'unreviewed').flatMap((finding) =>
    [finding.file, ...(finding.files ?? []).map((row) => row.path), ...(finding.mainFiles ?? []).map((row) => row.path)]))
  semantic.receipt = runtime.observation.finishExecution(semanticHandle, {
    exitCode: semantic.ok ? 0 : 1, validationStatus: semantic.ok ? 'passed' : 'failed',
    checks: semanticChecks, checkedFiles: [...semanticFiles].filter((file) => inventory.files.includes(file)),
    summary: { projectSemanticGate: semantic.ok ? 'passed' : 'failed', originalNativeExitCode: native.exitCode,
      originalReviewExitCode: review.exitCode, reviewedFindings: semantic.reviewedCount ?? 0, javaGates: 'unverified' },
  })
  writeFileSync(resolve(output, 'bd-robot-semantic-review.json'), JSON.stringify(semantic, null, 2) + '\n')
  const adapterHandle = runtime.observation.beginExecution(runtime.options(PROJECT_ROOT,
    { ...options, tool: 'robot-bd-static-adapter', readOnlyVerification: true,
      ruleFiles: ['scripts/tooling/robot_policy.mjs'], configFiles: ['.wl-skills-bd/rules.local.json', '.wl-skills-bd/review-baseline.json'] }))
  const adapter = evaluateRobot(readInputs(PROJECT_ROOT, inventory.files))
  const afterInventory = inputInventory(PROJECT_ROOT)
  const afterDigest = inputDigest(PROJECT_ROOT, afterInventory.files)
  const stable = beforeDigest === afterDigest
  adapter.inputScope = { files: inventory.files, excluded: inventory.excluded, beforeDigest, afterDigest, stable,
    policy: 'Exclude Maven module target and tool receipts; include every business file, including src/**/target/**.' }
  adapter.checks.push({ id: 'RBD_INPUT_SCOPE', status: stable ? 'pass' : 'fail' })
  adapter.ok &&= stable
  adapter.receipt = runtime.observation.finishExecution(adapterHandle, {
    exitCode: adapter.ok ? 0 : 1, validationStatus: adapter.ok ? 'passed' : 'failed',
    checks: adapter.checks, checkedFiles: adapter.checkedFiles.filter((file) => inventory.files.includes(file)),
    summary: { endpointCount: adapter.endpointCount, staticRobotChecks: adapter.ok ? 'passed' : 'failed', javaGates: 'unverified', inputStable: stable },
  })
  writeFileSync(resolve(output, 'bd-robot-adapter.json'), JSON.stringify(adapter, null, 2) + '\n')
  const receiptsComplete = [native.receipt, review.receipt, semantic.receipt, adapter.receipt]
    .every((receipt) => receipt.inputSnapshot.complete && !receipt.changedDuringCheck)
  console.log(JSON.stringify({
    native: { exitCode: native.exitCode, stats: native.report.stats, coverage: native.report.coverage },
    robot: { ok: review.report.ok, summary: review.report.qualityGate?.summary, coverage: review.report.ruleCoverage },
    semantic: { ok: semantic.ok, reviewedCount: semantic.reviewedCount, effectiveGate: semantic.effectiveGate,
      mandatoryBlockers: semantic.mandatoryBlockers, error: semantic.error },
    adapter: { ok: adapter.ok, checks: adapter.checks, endpoints: adapter.endpointCount, findings: adapter.findings,
      inputStable: stable, snapshotComplete: receiptsComplete, limitations: adapter.limitations },
    javaGates: 'unverified by this Node static executor; use actual Robot Java/MySQL verification evidence',
    nativeGate: native.exitCode === 0 ? 'passed' : 'failed; original findings retained, no baseline update',
    reports: ['target/verification/bd-native.json', 'target/verification/bd-robot-review.json',
      'target/verification/bd-robot-semantic-review.json', 'target/verification/bd-robot-adapter.json'],
  }, null, 2))
  return semantic.ok && adapter.ok && receiptsComplete ? 0 : 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = main() }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
