import assert from 'node:assert/strict'
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { createRequire } from 'node:module'
import test from 'node:test'
import { classifyFinding, deriveSemanticReview, evaluateSemanticGate, FIELD_TARGETS,
  MAIN_DIRECTORY, sha256, TEST_DIRECTORY, validateReviewPolicy } from './backend_semantic_review.mjs'

const require = createRequire(import.meta.url)
const { evaluateQualityGate, loadQualityGate } = require('@agile-team/wl-skills-bd/lib/quality-gate.js')
const PROJECT = resolve(new URL('../..', import.meta.url).pathname)
const approved = JSON.parse(readFileSync(resolve(PROJECT, 'quality/backend-semantic-reviews.json'), 'utf8'))
const config = loadQualityGate(PROJECT).config

function fixture(t) {
  const root = mkdtempSync(resolve(tmpdir(), 'robot exact review '))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const policy = structuredClone(approved)
  const files = new Set([...policy.fields.map((row) => row.file),
    ...policy.testDirectory.files.map((row) => `${TEST_DIRECTORY}/${row.name}`),
    ...policy.testDirectory.mainFiles.map((row) => `${MAIN_DIRECTORY}/${row.name}`)])
  for (const file of files) {
    mkdirSync(dirname(resolve(root, file)), { recursive: true })
    copyFileSync(resolve(PROJECT, file), resolve(root, file))
  }
  return { root, policy }
}

function replaceReviewed(input, file, text, refresh = true) {
  writeFileSync(resolve(input.root, file), text)
  if (!refresh) return
  for (const row of input.policy.fields.filter((row) => row.file === file)) row.sourceSHA256 = sha256(text)
  for (const [directory, rows] of [[TEST_DIRECTORY, input.policy.testDirectory.files],
    [MAIN_DIRECTORY, input.policy.testDirectory.mainFiles]]) {
    for (const row of rows) if (`${directory}/${row.name}` === file) row.sha256 = sha256(text)
  }
}

function fieldFinding(file, field, text) {
  const expression = new RegExp(`(?:private\\s+)?(?:String|boolean)\\s+${field}\\s*;`, 'g')
  const match = [...text.matchAll(expression)].at(-1)
  assert.ok(match, 'fixture must contain a concrete target declaration')
  return { rule: 'B25', severity: 'error', file,
    line: text.slice(0, match.index + match[0].indexOf(field)).split('\n').length,
    message: `疑似敏感字段 ${field} 会进入 Lombok toString；必须 @ToString.Exclude 并配置日志脱敏`,
    fingerprint: sha256(file + field) }
}

function b6(file = TEST_DIRECTORY) {
  return { rule: 'B6', severity: 'warn', file, line: 1,
    message: '单个源码目录含 23 个 Java 文件，超过 20 个分域建议', fingerprint: sha256(file) }
}

function reports(findings) {
  return [{ issues: findings, coverage: { status: 'complete' } },
    { _allFindings: findings, truncated: false, ruleCoverage: { status: 'complete' },
      qualityGate: { summary: { total: findings.length }, reasons: [{ code: 'QG_WARNING_BUDGET', message: 'raw remains failed' }],
        coverage: { reports: [], line: { ratio: null }, changedLine: { ratio: null }, diagnostics: [] } } }]
}

for (const target of FIELD_TARGETS) test(`actual exact ${target.className}.${target.field} annotation is retained`, (t) => {
  const input = fixture(t), text = readFileSync(resolve(input.root, target.file), 'utf8')
  const evidence = classifyFinding(input.root, fieldFinding(target.file, target.field, text), input.policy)
  assert.equal(evidence.disposition, 'verified-field-exclusion')
  assert.equal(evidence.sourceSHA256, sha256(text))
  assert.equal(evidence.field, target.field)
})

const fieldTarget = FIELD_TARGETS[0]
function example(body, imports = 'import lombok.ToString;') {
  return `package com.robot.system.api.entity;\n${imports}\npublic class SysUser {\n${body}\n}\n`
}

for (const [name, body, imports] of [
  ['same declaration', '@ToString.Exclude private String passwordHash;'],
  ['preceding annotations', '@ToString.Exclude\n@Deprecated\nprivate String passwordHash;'],
  ['fully qualified annotation', '@lombok.ToString.Exclude private String passwordHash;', ''],
]) test(`exact field parser accepts ${name}`, (t) => {
  const input = fixture(t), text = example(body, imports)
  replaceReviewed(input, fieldTarget.file, text)
  assert.equal(classifyFinding(input.root, fieldFinding(fieldTarget.file, 'passwordHash', text), input.policy)
    .disposition, 'verified-field-exclusion')
})

for (const [name, body, imports] of [
  ['missing annotation', 'private String passwordHash;'],
  ['neighbor annotation', '@ToString.Exclude private String other;\nprivate String passwordHash;'],
  ['comment annotation', '/* @ToString.Exclude */ private String passwordHash;'],
  ['string-literal annotation', 'private String other="@ToString.Exclude";\nprivate String passwordHash;'],
  ['text-block annotation', 'private String other="""\n@ToString.Exclude\n""";\nprivate String passwordHash;'],
  ['method-local declaration', 'void method() { @ToString.Exclude String passwordHash; }'],
  ['nested other class', 'static class Other { @ToString.Exclude private String passwordHash; }'],
  ['wrong annotation type', '@ToString.Exclude private String passwordHash;', 'import other.ToString;'],
  ['shadowed imported type', '@ToString.Exclude private String passwordHash;\nstatic class ToString {}'],
  ['manual toString override', '@ToString.Exclude private String passwordHash;\npublic String toString(){return passwordHash;}'],
  ['unknown Exclude arguments', '@ToString.Exclude(value=true) private String passwordHash;'],
]) test(`exact field parser rejects ${name}, even with freshly matching source hash`, (t) => {
  const input = fixture(t), text = example(body, imports)
  replaceReviewed(input, fieldTarget.file, text)
  assert.equal(classifyFinding(input.root, fieldFinding(fieldTarget.file, 'passwordHash', text), input.policy)
    .disposition, 'unreviewed')
})

test('wrong owner package and wrong finding line cannot borrow a real exclusion', (t) => {
  const input = fixture(t), text = example('@ToString.Exclude private String passwordHash;')
  replaceReviewed(input, fieldTarget.file, text)
  const finding = fieldFinding(fieldTarget.file, 'passwordHash', text)
  assert.equal(classifyFinding(input.root, { ...finding, line: finding.line + 1 }, input.policy).disposition, 'unreviewed')
  const moved = text.replace('com.robot.system.api.entity', 'other.entity')
  replaceReviewed(input, fieldTarget.file, moved)
  assert.equal(classifyFinding(input.root, fieldFinding(fieldTarget.file, 'passwordHash', moved), input.policy)
    .disposition, 'unreviewed')
})

test('source hash drift cannot silently refresh an approval or disappear without a finding', (t) => {
  const input = fixture(t), text = readFileSync(resolve(input.root, fieldTarget.file), 'utf8') + '\n'
  replaceReviewed(input, fieldTarget.file, text, false)
  assert.equal(classifyFinding(input.root, fieldFinding(fieldTarget.file, 'passwordHash', text), input.policy)
    .disposition, 'unreviewed')
  assert.throws(() => deriveSemanticReview(input.root, ...reports([]), input.policy), /hash drift/)
})

test('policy cannot add a fourth field or broaden the B6 path/count', () => {
  const field = structuredClone(approved)
  field.fields.push({ ...field.fields[0], field: 'secret' })
  assert.throws(() => validateReviewPolicy(field), /three/)
  for (const change of [{ path: MAIN_DIRECTORY }, { path: 'src/test/java/other' }, { javaFileCount: 24 }]) {
    const policy = structuredClone(approved)
    Object.assign(policy.testDirectory, change)
    assert.throws(() => validateReviewPolicy(policy), /exact 23-file/)
  }
})

test('real 23-file package proof includes every hash, its shared fixture and current main facts', (t) => {
  const input = fixture(t), evidence = classifyFinding(input.root, b6(), input.policy)
  assert.equal(evidence.disposition, 'reviewed-test-colocation')
  assert.equal(evidence.files.length, 23)
  assert.equal(evidence.mainFiles.length, approved.testDirectory.mainFiles.length)
  assert.ok(evidence.fixtureConsumers.length >= 2)
  assert.deepEqual(evidence.internals.map((row) => row.className.split('.').at(-1)), ['AuthFamilyStates', 'AuthLogoutAudit'])
})

test('main or any other directory B6 remains blocked at the unchanged warning budget zero', (t) => {
  const input = fixture(t)
  for (const directory of [MAIN_DIRECTORY, 'robot-services/robot-system/src/test/java/com/robot/system/role/service']) {
    const [native, review] = reports([b6(directory)])
    const semantic = deriveSemanticReview(input.root, native, review, input.policy)
    assert.equal(semantic.mandatoryBlockers.length, 1)
    const gate = evaluateSemanticGate(review, semantic, { config }, evaluateQualityGate)
    assert.equal(gate.warningBudget, 0)
    assert.equal(gate.ok, false)
    assert.ok(gate.gateReasons.some((reason) => reason.code === 'QG_WARNING_BUDGET'))
  }
})

for (const kind of ['added file', 'changed hash', 'package mismatch', 'public fixture']) test(`B6 rejects ${kind}`, (t) => {
  const input = fixture(t), file = `${TEST_DIRECTORY}/AuthStateTestSupport.java`
  const text = readFileSync(resolve(input.root, file), 'utf8')
  if (kind === 'added file') writeFileSync(resolve(input.root, TEST_DIRECTORY, 'Extra.java'), 'class Extra {}')
  if (kind === 'changed hash') replaceReviewed(input, file, text + '\n', false)
  if (kind === 'package mismatch') replaceReviewed(input, file, text.replace('package com.robot.auth.authn.service;', 'package other;'))
  if (kind === 'public fixture') replaceReviewed(input, file, text.replace('final class AuthStateTestSupport', 'public final class AuthStateTestSupport'))
  assert.equal(classifyFinding(input.root, b6(), input.policy).disposition, 'unreviewed')
})

test('symlinked reviewed field is rejected', (t) => {
  const input = fixture(t), destination = resolve(input.root, fieldTarget.file)
  const text = readFileSync(destination, 'utf8'), alternative = resolve(input.root, 'same-content.java')
  writeFileSync(alternative, text); rmSync(destination); symlinkSync(alternative, destination)
  assert.equal(classifyFinding(input.root, fieldFinding(fieldTarget.file, 'passwordHash', text), input.policy)
    .disposition, 'unreviewed')
})

test('derived evidence keeps original reports immutable and still blocks unrelated errors/reasons', (t) => {
  const input = fixture(t), text = readFileSync(resolve(input.root, fieldTarget.file), 'utf8')
  const [native, review] = reports([b6(), fieldFinding(fieldTarget.file, 'passwordHash', text)])
  const before = JSON.stringify([native, review])
  const semantic = deriveSemanticReview(input.root, native, review, input.policy)
  assert.equal(JSON.stringify([native, review]), before)
  assert.equal(semantic.reviewedCount, 2)
  assert.equal(evaluateSemanticGate(review, semantic, { config }, evaluateQualityGate).ok, true)
  review.qualityGate.reasons.push({ code: 'UNKNOWN_GATE_REASON', message: 'must remain blocking' })
  assert.equal(evaluateSemanticGate(review, semantic, { config }, evaluateQualityGate).ok, false)
  const unrelated = { rule: 'B13', severity: 'error', file: 'NewCache.java', line: 1, message: 'No TTL' }
  const next = reports([...native.issues, unrelated])
  const unknown = deriveSemanticReview(input.root, ...next, input.policy)
  assert.equal(evaluateSemanticGate(next[1], unknown, { config }, evaluateQualityGate).ok, false)
})

test('partial, truncated or inconsistent raw coverage cannot become a reviewed pass', (t) => {
  const input = fixture(t)
  for (const change of ['native partial', 'review partial', 'truncated', 'wrong total']) {
    const [native, review] = reports([])
    if (change === 'native partial') native.coverage.status = 'partial'
    if (change === 'review partial') review.ruleCoverage.status = 'partial'
    if (change === 'truncated') review.truncated = true
    if (change === 'wrong total') review.qualityGate.summary.total = 1
    assert.throws(() => deriveSemanticReview(input.root, native, review, input.policy), /Complete/)
  }
})
