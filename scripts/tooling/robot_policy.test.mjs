import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import test from 'node:test'
import { PROJECT_ROOT } from '../check_backend_rules.mjs'
import { evaluateRobot, inputDigest, inputInventory, readInputs } from './robot_policy.mjs'

const original = readInputs(PROJECT_ROOT)
const source = (suffix) => [...original.keys()].find((file) => file.endsWith(suffix))
const baselineFile = resolve(PROJECT_ROOT, '.wl-skills-bd/review-baseline.json')
const baselineHash = () => createHash('sha256').update(readFileSync(baselineFile)).digest('hex')
const baselineBefore = baselineHash()
function mutation(file, before, after) {
  const inputs = new Map(original)
  const text = inputs.get(file)
  assert.ok(before instanceof RegExp ? before.test(text) : text.includes(before), `Mutation fixture missing: ${file} ${before}`)
  inputs.set(file, text.replace(before, after))
  return evaluateRobot(inputs)
}
function blocks(report, rule) {
  assert.equal(report.ok, false)
  assert.ok(report.findings.some((finding) => finding.rule === rule), JSON.stringify(report.findings))
}

test('actual Robot declarations cover every parsed MVC endpoint with exact policies and contracts', () => {
  const report = evaluateRobot(original)
  assert.equal(report.ok, true, JSON.stringify(report.findings))
  assert.ok(report.endpointCount >= 79)
  assert.equal(report.endpoints.filter((endpoint) => endpoint.handler === 'createForm').length, 1,
    'multiline multipart mapping must not disappear from coverage')
  assert.ok(report.endpoints.some((endpoint) => endpoint.policy === 'explicit-public'))
  assert.ok(report.endpoints.some((endpoint) => endpoint.policy === 'trusted-inner'))
  assert.ok(report.endpoints.some((endpoint) => endpoint.policy === 'sa-token-permission'))
  assert.ok(report.checks.every((check) => !/^B\d|^J\d/.test(check.id)), 'Robot checks cannot forge native B/J gates')
})

test('removing a real Sa-Token permission, including class permission, is blocked', () => {
  blocks(mutation(source('/user/controller/UserController.java'), '@SaCheckPermission("sys:user:save")',
    '// @SaCheckPermission("sys:user:save")'), 'RBD_AUTHORIZATION')
  blocks(mutation(source('/permission/controller/CacheOutboxGovernanceController.java'),
    '@SaCheckPermission("sys:cache-outbox:manage")', ''), 'RBD_AUTHORIZATION')
})

test('class or method SaIgnore bypass is blocked even when a permission remains', () => {
  blocks(mutation(source('/user/controller/UserController.java'), '@RestController',
    '@RestController\n@SaIgnore'), 'RBD_AUTHORIZATION')
})

test('new unregistered public/self/inner endpoint cannot inherit a broad directory exemption', () => {
  for (const [suffix, annotation, expected] of [
    ['/authn/controller/AuthController.java', '@PostMapping("/login")', 'RBD_AUTHORIZATION'],
    ['/account/controller/AccountController.java', '@GetMapping("/profile")', 'RBD_AUTHORIZATION'],
    ['/user/controller/inner/UserInnerController.java', '@GetMapping("/inner/user/by-username")', 'RBD_INNER_TRUST'],
  ]) blocks(mutation(source(suffix), annotation,
    annotation.replace(/"[^"\n]+"/, expected === 'RBD_INNER_TRUST' ? '"/inner/unregistered"' : '"/unregistered"')), expected)
})

test('widened service or gateway public whitelist and property defaults are blocked', () => {
  blocks(mutation(source('robot-auth/src/main/resources/application.yml'), '- /auth/login\n', '- /auth/**\n'), 'RBD_PUBLIC_PATHS')
  blocks(mutation(source('robot-gateway/src/main/resources/application.yml'), '- /auth/login\n', '- /auth/**\n'), 'RBD_PUBLIC_PATHS')
  blocks(mutation(source('/gateway/properties/AuthProperties.java'), '"/auth/login",', '"/auth/**",'), 'RBD_PUBLIC_PATHS')
})

test('removing internal interceptor or delegated membership guard is blocked', () => {
  blocks(mutation(source('/security/config/RobotSecurityAutoConfiguration.java'),
    'new InnerCallInterceptor(properties)', 'new DifferentInterceptor(properties)'), 'RBD_INNER_TRUST')
  blocks(mutation(source('/security/InnerSystemAccess.java'),
    'AuthUtil.requireTrustedUser(userId, companyId);', '// AuthUtil.requireTrustedUser(userId, companyId);'), 'RBD_INNER_TRUST')
  blocks(mutation(source('/menu/controller/inner/MenuInnerController.java'),
    'innerAccess.requireActiveMembership(userId, companyId);', ''), 'RBD_INNER_TRUST')
})

test('removing trusted service supply or moving it after a mapper access is blocked', () => {
  const file = source('/user/service/UserSupplyService.java')
  blocks(mutation(file, 'AuthUtil.requireTrustedService();', '// AuthUtil.requireTrustedService();'), 'RBD_INNER_TRUST')
  blocks(mutation(file, 'AuthUtil.requireTrustedService();', 'userMapper.selectById("unsafe");\n        AuthUtil.requireTrustedService();'), 'RBD_INNER_TRUST')
})

test('default subject validation assembly cannot disappear', () => {
  blocks(mutation(source('/security/config/RobotSecurityAutoConfiguration.java'),
    'new LoginSessionValidationInterceptor(sessionStateVerifier)', 'new DifferentInterceptor(sessionStateVerifier)'), 'RBD_AUTHORIZATION')
})

test('Robot soft delete facts and actual runtime MyBatis configuration must agree', () => {
  blocks(mutation('.wl-skills-bd/rules.local.json', '"activeValue": 1', '"activeValue": 0'), 'RBD_SOFT_DELETE')
  blocks(mutation(source('robot-system/src/main/resources/application.yml'),
    'logic-delete-value: 0', 'logic-delete-value: 1'), 'RBD_SOFT_DELETE')
})

test('Robot tenant defaults, actual plugin ordering and trusted source are checked', () => {
  blocks(mutation(source('/mybatis/properties/TenantProperties.java'), 'boolean enabled = true;', 'boolean enabled = false;'), 'RBD_TENANT')
  blocks(mutation(source('/mybatis/config/RobotMybatisAutoConfiguration.java'),
    /interceptor\.addInnerInterceptor\s*\(\s*new TenantLineInnerInterceptor\(/,
    'different.addInnerInterceptor(new TenantLineInnerInterceptor('), 'RBD_TENANT')
  blocks(mutation(source('/mybatis/tenant/RobotTenantLineHandler.java'),
    'String companyId = AuthUtil.getLoginCompanyId();', 'String companyId = request.getCompanyId();'), 'RBD_TENANT')
})

test('PageQuery cap and long multiplication regressions are blocked', () => {
  blocks(mutation(source('/core/domain/PageQuery.java'),
    'Math.min(pageSize, RobotConstants.MAX_PAGE_SIZE)', 'pageSize'), 'RBD_PAGE_QUERY')
  blocks(mutation(source('/core/domain/PageQuery.java'),
    '(long) (pageValue() - 1) * pageSizeValue()', '(long) ((pageValue() - 1) * pageSizeValue())'), 'RBD_PAGE_QUERY')
})

test('custom Robot contracts block permission/route/envelope drift instead of generating CRUD', () => {
  blocks(mutation('contracts/system/users.json', '"permission": "sys:user:save"',
    '"permission": "sys:user:unsafe"'), 'RBD_HTTP_CONTRACTS')
  blocks(mutation('contracts/system/users.json', '"successCode": 0', '"successCode": 2000'), 'RBD_HTTP_CONTRACTS')
  blocks(mutation(source('/menu/controller/MenuController.java'), '@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)',
    '@PostMapping(path = "/unregistered", consumes = MediaType.APPLICATION_JSON_VALUE)'), 'RBD_HTTP_CONTRACTS')
})

test('unsupported dynamic MVC paths fail instead of claiming complete endpoint coverage', () => {
  blocks(mutation(source('/user/controller/UserController.java'), '@RequestMapping("/sys/users")',
    '@RequestMapping(path = UNKNOWN_PATH)'), 'RBD_AUTHORIZATION')
})

test('input snapshot excludes only physical artifacts; generated-name business files remain', (t) => {
  const root = mkdtempSync(resolve(tmpdir(), 'robot-snapshot-'))
  t.after(() => rmSync(root, { force: true, recursive: true }))
  function write(file, value) { mkdirSync(resolve(root, file, '..'), { recursive: true }); writeFileSync(resolve(root, file), value) }
  write('pom.xml', '<project/>')
  write('module/pom.xml', '<project/>')
  write('module/target/generated.java', 'output')
  write('target/verification/bd-native.json', 'receipt')
  write('.wl-skills-bd/runs/run/result.json', 'receipt')
  write('.wl-skills-bd/.state/executor.json', 'cache')
  write('module/src/main/java/target/Business.java', 'first')
  write('module/src/main/java/node_modules/Business.java', 'source, not installed tool')
  write('module/src/main/resources/bd-native.json', 'business contract')
  write('contracts/target/business.json', 'business contract')
  const before = inputInventory(root)
  assert.deepEqual(before.files, ['contracts/target/business.json', 'module/pom.xml',
    'module/src/main/java/node_modules/Business.java', 'module/src/main/java/target/Business.java',
    'module/src/main/resources/bd-native.json', 'pom.xml'])
  const digest = inputDigest(root, before.files)
  write('target/verification/bd-native.json', 'new receipt')
  write('module/target/generated.java', 'new output')
  write('.wl-skills-bd/runs/run/result.json', 'new receipt')
  assert.equal(inputDigest(root, inputInventory(root).files), digest)
  write('module/src/main/java/target/Business.java', 'changed business')
  assert.notEqual(inputDigest(root, inputInventory(root).files), digest)
  const changed = inputDigest(root, inputInventory(root).files)
  write('module/src/main/java/NewBusiness.java', 'added during scan')
  assert.notEqual(inputDigest(root, inputInventory(root).files), changed)
})

test('adapter never updates the native review baseline', () => {
  assert.equal(baselineHash(), baselineBefore)
})
