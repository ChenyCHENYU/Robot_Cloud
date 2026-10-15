// Derived project evidence only. Never mutate native reports, baselines, budgets or Java source.
import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, readdirSync } from 'node:fs'
import { basename, resolve } from 'node:path'
import { excludedField, javaClass, javaTokens } from './java_field_review.mjs'

export const FIELD_TARGETS = Object.freeze([
  { file: 'robot-services/robot-system/src/main/java/com/robot/system/api/entity/SysUser.java',
    className: 'com.robot.system.api.entity.SysUser', field: 'passwordHash', type: 'String' },
  { file: 'robot-services/robot-system/src/main/java/com/robot/system/api/entity/SysUser.java',
    className: 'com.robot.system.api.entity.SysUser', field: 'email', type: 'String' },
  { file: 'robot-services/robot-system/src/main/java/com/robot/system/api/dto/user/UserSaveRequest.java',
    className: 'com.robot.system.api.dto.user.UserSaveRequest', field: 'emailPresent', type: 'boolean' },
])
export const TEST_DIRECTORY = 'robot-services/robot-auth/src/test/java/com/robot/auth/authn/service'
export const MAIN_DIRECTORY = 'robot-services/robot-auth/src/main/java/com/robot/auth/authn/service'
export const AUTH_PACKAGE = 'com.robot.auth.authn.service'
export const TEST_FILE_COUNT = 23
export const sha256 = (value) => createHash('sha256').update(value).digest('hex')

function require(condition, message) { if (!condition) throw new Error(message) }
const fieldKey = (entry) => `${entry.file}:${entry.className}:${entry.field}:${entry.type}`

export function validateReviewPolicy(policy) {
  require(policy?.schemaVersion === 1, 'Unsupported project semantic review policy')
  require(Array.isArray(policy.fields) && policy.fields.length === FIELD_TARGETS.length,
    'Only the three explicitly reviewed B25 fields are supported')
  const actual = policy.fields.map(fieldKey).sort(), expected = FIELD_TARGETS.map(fieldKey).sort()
  require(JSON.stringify(actual) === JSON.stringify(expected), 'B25 policy scope drift')
  for (const field of policy.fields) require(/^[a-f0-9]{64}$/.test(field.sourceSHA256)
    && typeof field.reason === 'string' && field.reason.length > 20, 'Missing exact B25 source review evidence')
  const directory = policy.testDirectory
  require(directory?.path === TEST_DIRECTORY && directory.package === AUTH_PACKAGE
    && directory.javaFileCount === TEST_FILE_COUNT && directory.mainPath === MAIN_DIRECTORY,
  'Only the exact 23-file Auth test package is reviewed for B6')
  for (const rows of [directory.files, directory.mainFiles]) {
    require(Array.isArray(rows) && rows.length > 0 && new Set(rows.map((row) => row.name)).size === rows.length,
      'Missing or duplicate directory review inputs')
    for (const row of rows) require(/^[A-Za-z_$][A-Za-z0-9_$]*\.java$/.test(row.name)
      && /^[a-f0-9]{64}$/.test(row.sha256), 'Invalid reviewed directory input')
  }
  require(directory.files.length === TEST_FILE_COUNT && typeof directory.reason === 'string'
    && directory.reason.length > 40, 'Missing B6 scope justification')
  return policy
}

function source(root, file) {
  const absolute = resolve(root, file)
  require(absolute.startsWith(resolve(root) + '/'), 'Reviewed source escapes project')
  const stat = lstatSync(absolute)
  require(stat.isFile() && !stat.isSymbolicLink(), 'Reviewed source must be a regular file')
  return readFileSync(absolute, 'utf8')
}

function reviewedDirectory(root, directory, expected) {
  const actualNames = readdirSync(resolve(root, directory)).sort()
  require(JSON.stringify(actualNames) === JSON.stringify(expected.map((row) => row.name).sort()),
    `Reviewed directory inventory drift: ${directory}`)
  return actualNames.map((name) => {
    const path = `${directory}/${name}`, text = source(root, path), hash = sha256(text)
    require(expected.find((row) => row.name === name)?.sha256 === hash, `Reviewed directory source drift: ${path}`)
    const owner = javaClass(text, basename(name, '.java'))
    require(owner.packageName === AUTH_PACKAGE, `Auth package drift: ${path}`)
    return { path, sha256: hash, owner, text }
  })
}

function directoryProof(root, policy) {
  const directory = policy.testDirectory
  const tests = reviewedDirectory(root, TEST_DIRECTORY, directory.files)
  const main = reviewedDirectory(root, MAIN_DIRECTORY, directory.mainFiles)
  const fixture = tests.find((row) => row.owner.name === 'AuthStateTestSupport')
  require(fixture?.owner.kind === 'class' && fixture.owner.packagePrivate,
    'AuthStateTestSupport is not the reviewed package-private fixture')
  const consumers = tests.filter((row) => row !== fixture && javaTokens(row.text)
    .some((token) => token.kind === 'identifier' && token.value === fixture.owner.name))
  require(consumers.length >= 2, 'Shared fixture no longer requires same-package tests')
  const internals = ['AuthFamilyStates', 'AuthLogoutAudit'].map((name) => {
    const row = main.find((candidate) => candidate.owner.name === name)
    require(row?.owner.kind === 'class' && row.owner.packagePrivate, `Production collaborator scope drift: ${name}`)
    const clients = tests.filter((test) => javaTokens(test.text).some((token) => token.value === name))
    require(clients.length > 0, `No direct test of reviewed package-private collaborator: ${name}`)
    return { path: row.path, className: `${AUTH_PACKAGE}.${name}`, consumers: clients.map((test) => test.path) }
  })
  return { testJavaFiles: tests.length, mainJavaFiles: main.length, reason: directory.reason,
    files: tests.map(({ path, sha256 }) => ({ path, sha256 })),
    mainFiles: main.map(({ path, sha256 }) => ({ path, sha256 })),
    packagePrivateFixture: fixture.path, fixtureConsumers: consumers.map((row) => row.path), internals }
}

export function classifyFinding(root, finding, policy) {
  const evidence = { rule: finding.rule, file: finding.file, line: finding.line,
    fingerprint: finding.fingerprint, originalSeverity: finding.severity, disposition: 'unreviewed' }
  try {
    validateReviewPolicy(policy)
    if (finding.rule === 'B25') {
      const field = /^疑似敏感字段 ([A-Za-z_$][A-Za-z0-9_$]*) 会进入 Lombok toString；必须 @ToString.Exclude 并配置日志脱敏$/.exec(finding.message)?.[1]
      const approved = policy.fields.find((row) => row.file === finding.file && row.field === field)
      require(approved, 'B25 finding is outside the exact reviewed field scope')
      const text = source(root, approved.file), hash = sha256(text)
      require(hash === approved.sourceSHA256, 'B25 reviewed source hash drift')
      const declaration = excludedField(text, { ...approved, line: finding.line })
      require(declaration, 'Exact field-attached Lombok exclusion was not proven')
      return { ...evidence, disposition: 'verified-field-exclusion', ...declaration,
        sourceSHA256: hash, reason: approved.reason }
    }
    if (finding.rule === 'B6') {
      require(finding.file === TEST_DIRECTORY && finding.severity === 'warn'
        && finding.line === 1 && finding.message === '单个源码目录含 23 个 Java 文件，超过 20 个分域建议',
      'B6 finding is outside the exact reviewed test-directory advisory')
      return { ...evidence, disposition: 'reviewed-test-colocation', ...directoryProof(root, policy) }
    }
    evidence.reason = 'No project semantic review applies to this finding'
  } catch (error) { evidence.reason = error.message }
  return evidence
}

export function deriveSemanticReview(root, nativeReport, reviewReport, policy) {
  validateReviewPolicy(policy)
  for (const approved of policy.fields) require(sha256(source(root, approved.file)) === approved.sourceSHA256,
    `B25 reviewed source hash drift: ${approved.file}`)
  const directoryEvidence = directoryProof(root, policy)
  require(Array.isArray(nativeReport.issues) && nativeReport.coverage?.status === 'complete',
    'Complete untouched native report required')
  require(Array.isArray(reviewReport._allFindings) && reviewReport.ruleCoverage?.status === 'complete'
    && !reviewReport.truncated && reviewReport.qualityGate?.summary?.total === reviewReport._allFindings.length,
  'Complete untruncated untouched Robot review required')
  const native = nativeReport.issues.map((finding) => classifyFinding(root, finding, policy))
  const review = reviewReport._allFindings.map((finding) => classifyFinding(root, finding, policy))
  const effectiveFindings = reviewReport._allFindings.filter((finding, index) => review[index].disposition === 'unreviewed')
  const mandatoryBlockers = native.filter((finding) => ['B6', 'B25'].includes(finding.rule)
    && finding.disposition === 'unreviewed')
  return { schemaVersion: 1, nativeClassifications: native, reviewClassifications: review,
    effectiveFindings, mandatoryBlockers, reviewedCount: review.length - effectiveFindings.length,
    directoryEvidence,
    policySHA256: sha256(JSON.stringify(policy)),
    limitations: ['Original native and Robot reports retain their findings, failures and fingerprints.',
      'Only the exact pinned field declarations/test package have project semantic review.',
      'B1/B17 native limitations and existing baseline remain visible; this is not a native-all-rules pass.',
      'This static evidence does not certify runtime privacy, architecture, Java or production gates.'] }
}

export function evaluateSemanticGate(reviewReport, semantic, inputs, evaluateQualityGate) {
  const effectiveGate = evaluateQualityGate({ ...inputs, findings: semantic.effectiveFindings,
    coverage: reviewReport.qualityGate.coverage, ruleCoverage: reviewReport.ruleCoverage })
  const knownReasons = new Set(['QG_RULE_COVERAGE', 'QG_WARNING_BUDGET', 'QG_COVERAGE_REQUIRED',
    'QG_LINE_COVERAGE', 'QG_CHANGED_COVERAGE'])
  const residualReasons = reviewReport.qualityGate.reasons.filter((reason) => !knownReasons.has(reason.code))
  return { ...effectiveGate, originalResidualReasons: residualReasons,
    warningBudget: inputs.config.warningBudget, baselineMode: inputs.config.baselineMode,
    ok: effectiveGate.ok && !semantic.mandatoryBlockers.length && !residualReasons.length }
}
