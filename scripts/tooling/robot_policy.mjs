// Robot-specific static evidence. This does not certify runtime authorization or Java gates.
import { createHash } from 'node:crypto'
import { existsSync, lstatSync, readFileSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { basename, resolve } from 'node:path'

const require = createRequire(import.meta.url)
const { methodDeclarations } = require('@agile-team/wl-skills-bd/lib/be-rules.js')
const CORE = 'robot-common/robot-common-core/src/main/java/com/robot/common/core'
const SECURITY = 'robot-common/robot-common-security/src/main/java/com/robot/common/security'
const MYBATIS = 'robot-common/robot-common-mybatis/src/main/java/com/robot/common/mybatis'
const SYSTEM = 'robot-services/robot-system/src/main/java/com/robot/system'
const AUTH = 'robot-services/robot-auth/src/main/java/com/robot/auth/authn'
const AUTH_YML = 'robot-services/robot-auth/src/main/resources/application.yml'
const SYSTEM_YML = 'robot-services/robot-system/src/main/resources/application.yml'
const GATEWAY_YML = 'robot-gateway/src/main/resources/application.yml'
const PUBLIC = new Set(['POST /auth/login', 'POST /auth/login-companies',
  'POST /auth/context/activate', 'POST /auth/refresh-token'])
const AUTHENTICATED = new Set(['POST /auth/context/switch', 'GET /auth/menu-list',
  'GET /auth/button-list', 'GET /auth/data-permission'])
const SELF = new Set(['GET /account/profile', 'PUT /account/profile', 'PUT /account/password',
  'GET /account/login-records', 'GET /account/security-settings', 'GET /account/activity-logs'])
const LOOKUPS = new Map([['GET /sys/dictionaries/by-code', ['DictController', 'itemsByCode']],
  ['GET /sys/roles/options', ['RoleController', 'options']]])
const INNER = new Map([
  ['GET /inner/user/by-username', ['UserInnerController', 'getByUsername', 'findAuthenticationUserByUsername', 'AuthUtil.requireTrustedService()']],
  ['GET /inner/user/{userId}', ['UserInnerController', 'getById', 'findAuthenticationUserById', 'innerAccess.requireUser(userId)']],
  ['GET /inner/user/{userId}/contexts', ['UserInnerController', 'getContexts', 'listLoginContexts', 'innerAccess.requireUser(userId)']],
  ['GET /inner/menu/{companyId}/routes', ['MenuInnerController', 'routes', null, 'innerAccess.requireActiveMembership(userId, companyId)']],
  ['GET /inner/menu/{companyId}/button-list', ['MenuInnerController', 'buttonList', null, 'innerAccess.requireActiveMembership(userId, companyId)']],
  ['GET /inner/permission/{companyId}/data-scope', ['MenuInnerController', 'dataScopes', null, 'innerAccess.requireActiveMembership(userId, companyId)']],
  ['POST /inner/log/login-record', ['LoginLogInnerController', 'recordLogin', 'recordLogin', 'AuthUtil.requireTrustedService()']],
])

// Exclusions are physical artifact boundaries, never filename patterns within source trees.
export function inputInventory(root) {
  const files = [], excluded = []
  function walk(relative = '', source = false) {
    const directory = resolve(root, relative)
    for (const entry of readdirSync(directory).sort()) {
      const path = relative ? `${relative}/${entry}` : entry
      const stat = lstatSync(resolve(root, path))
      if (!source && (entry === '.git' || entry === 'node_modules'
          || ['.wl-skills-bd/runs', '.wl-skills-bd/.state', '.wl-skills/runs'].includes(path)
          || (entry === 'target' && existsSync(resolve(directory, 'pom.xml')))
          || (!relative && (['.env', '.npmrc', 'env.local.json'].includes(entry) || entry.startsWith('.env.'))))) {
        excluded.push(path); continue
      }
      if (stat.isSymbolicLink()) throw new Error(`Input symlink requires explicit review: ${path}`)
      if (stat.isDirectory()) walk(path, source || entry === 'src')
      else if (stat.isFile()) files.push(path)
    }
  }
  walk()
  return { files, excluded }
}

export function inputDigest(root, files) {
  const hash = createHash('sha256')
  for (const file of files) hash.update(file).update('\0').update(readFileSync(resolve(root, file))).update('\0')
  return hash.digest('hex')
}

export function readInputs(root, files = inputInventory(root).files) {
  return new Map(files.filter((file) => /\.(java|ya?ml|json)$/.test(file))
    .map((file) => [file, readFileSync(resolve(root, file), 'utf8')]))
}

// Preserve strings and line positions: comments must not satisfy an assertion or create endpoints.
function uncomment(text) {
  let result = '', state = 'code'
  for (let i = 0; i < text.length; i++) {
    const c = text[i], n = text[i + 1]
    if (state === 'code' && c === '/' && (n === '/' || n === '*')) {
      result += '  '; i++; state = n === '/' ? 'line' : 'block'; continue
    }
    if (state === 'line') { result += c === '\n' ? '\n' : ' '; if (c === '\n') state = 'code'; continue }
    if (state === 'block') {
      if (c === '*' && n === '/') { result += '  '; i++; state = 'code' }
      else result += c === '\n' ? '\n' : ' '
      continue
    }
    result += c
    if (state === 'string' || state === 'char') {
      if (c === '\\') { result += text[++i] ?? '' }
      else if (c === (state === 'string' ? '"' : "'")) state = 'code'
    } else if (c === '"' || c === "'") state = c === '"' ? 'string' : 'char'
  }
  return result
}

const compact = (text) => text.replace(/\s+/g, '')
function methodInfo(content, handler) {
  const methods = methodDeclarations(content)
  const method = methods.find((candidate) => candidate.name === handler)
  if (!method) return null
  const classHeader = /\bclass\s+\w+[^\{]*\{/.exec(content)
  const classEndLine = classHeader ? content.slice(0, classHeader.index + classHeader[0].length).split('\n').length : 0
  const previous = Math.max(classEndLine, ...methods.filter((candidate) => candidate.endLine < method.startLine).map((candidate) => candidate.endLine))
  const annotations = content.split('\n').slice(previous, method.startLine - 1).join('\n')
  const classAnnotations = content.slice(0, content.search(/\bclass\s+/))
  const permissionText = /@SaCheckPermission\s*\(([^)]*)\)/.exec(annotations)
    ?? /@SaCheckPermission\s*\(([^)]*)\)/.exec(classAnnotations)
  const permissions = permissionText ? [...permissionText[1].matchAll(/"([^"\n]+)"/g)].map((m) => m[1]).sort() : []
  return { ...method, annotations, permissions }
}

function mappingPaths(argumentsText = '') {
  const assigned = /(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/.exec(argumentsText)
  const positional = /^(\{[^}]*\}|"[^"]*")/.exec(argumentsText.trim())
  const text = assigned?.[1] ?? positional?.[1]
  if (!text) {
    if (/\b(?:value|path)\s*=/.test(argumentsText)) throw new Error('Nonliteral MVC path requires semantic review')
    return ['']
  }
  return [...text.matchAll(/"([^"\n]*)"/g)].map((match) => match[1])
}

// BD's parser misses multiline consumes annotations. Reuse its method parser and resolve
// literal Spring MVC mappings here; unknown/variable path forms fail rather than disappearing.
function controllerEndpoints(contents) {
  const endpoints = []
  for (const [file, text] of contents) {
    if (!file.endsWith('Controller.java')) {
      if (/@(?:RestController|Controller)\b/.test(text)) throw new Error(`Controller naming outside checked convention: ${file}`)
      continue
    }
    const prefix = text.slice(0, text.search(/\bclass\s+/))
    const classMapping = /@RequestMapping\b\s*(?:\(([^)]*)\))?/.exec(prefix)
    const classPaths = classMapping ? mappingPaths(classMapping[1]) : ['']
    let parsedMappings = 0
    for (const method of methodDeclarations(text)) {
      const info = methodInfo(text, method.name)
      if (method.visibility !== 'public') continue
      for (const mapping of info.annotations.matchAll(/@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(?:\(([^)]*)\))?/g)) {
        parsedMappings++
        const methods = mapping[1] === 'Request' ? [...(mapping[2] ?? '').matchAll(/RequestMethod\.(\w+)/g)].map((m) => m[1]) : [mapping[1].toUpperCase()]
        for (const verb of methods.length ? methods : ['ANY']) for (const parent of classPaths) for (const child of mappingPaths(mapping[2])) {
          endpoints.push({ file, handler: method.name, line: method.startLine, method: verb,
            path: `/${parent}/${child}`.replace(/\/+/g, '/').replace(/\/$/, '') || '/' })
        }
      }
    }
    const expectedMappings = [...text.slice(prefix.length).matchAll(/@(Get|Post|Put|Delete|Patch|Request)Mapping\b/g)].length
    if (parsedMappings !== expectedMappings) throw new Error(`Unparsed MVC mapping in ${file}; runtime contract review required`)
  }
  return endpoints.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method) || a.handler.localeCompare(b.handler))
}

const simpleType = (text) => compact(text).replace(/\b\w+(?:\.\w+)+\b/g, (qualified) => qualified.split('.').at(-1))

// Read one explicit YAML block, refusing flow lists/unresolved constructs rather than guessing.
function yamlList(text, name) {
  const lines = text.split('\n'), lists = []
  for (let i = 0; i < lines.length; i++) {
    const match = new RegExp(`^(\\s*)${name}:\\s*(?:#.*)?$`).exec(lines[i])
    if (!match) continue
    const values = []
    for (let j = i + 1; j < lines.length; j++) {
      const line = lines[j].replace(/\s+#.*$/, '')
      if (!line.trim() || line.trim().startsWith('#')) continue
      if (line.search(/\S/) <= match[1].length) break
      const value = /^\s*-\s*(\/\S+)\s*$/.exec(line)?.[1]
      if (!value) throw new Error(`Unsupported ${name} YAML entry`)
      values.push(value)
    }
    lists.push(values)
  }
  if (!lists.length) throw new Error(`Missing ${name} YAML block`)
  return lists.flat()
}

export function evaluateRobot(inputs) {
  const checkedFiles = new Set(), findings = [], checks = [], endpointsEvidence = []
  const contents = new Map([...inputs].filter(([file]) => file.includes('/src/main/java/') && file.endsWith('.java'))
    .map(([file, text]) => [file, uncomment(text)]))
  let endpoints = [], parseError
  try { endpoints = controllerEndpoints(contents) } catch (error) { parseError = error.message }
  function read(file) {
    checkedFiles.add(file)
    if (!inputs.has(file)) throw new Error(`Missing Robot evidence ${file}`)
    return file.endsWith('.java') ? uncomment(inputs.get(file)) : inputs.get(file)
  }
  function need(condition, file, message) { if (!condition) findings.push({ rule: currentRule, file, message, severity: 'error' }) }
  let currentRule
  function check(id, fn) {
    currentRule = id
    const before = findings.length
    try { fn() } catch (error) { findings.push({ rule: id, file: '.', message: error.message, severity: 'error' }) }
    checks.push({ id, status: findings.length === before ? 'pass' : 'fail' })
  }
  check('RBD_AUTHORIZATION', () => {
    need(!parseError, '.', parseError ?? '')
    need(endpoints.length > 0, '.', 'No parsed MVC endpoints; coverage cannot be claimed')
    for (const endpoint of endpoints) {
      read(endpoint.file)
      const info = methodInfo(contents.get(endpoint.file), endpoint.handler)
      const key = `${endpoint.method} ${endpoint.path}`
      const controller = basename(endpoint.file, '.java')
      let policy
      if (PUBLIC.has(key) && controller === 'AuthController') policy = 'explicit-public'
      else if (AUTHENTICATED.has(key) && controller === 'AuthController') policy = 'authenticated-session'
      else if (key === 'POST /auth/logout' && controller === 'AuthController') policy = 'family-owned-logout'
      else if (SELF.has(key) && controller === 'AccountController') policy = 'current-subject-self-service'
      else if (LOOKUPS.get(key)?.[0] === controller && LOOKUPS.get(key)?.[1] === endpoint.handler) policy = 'authenticated-company-lookup'
      else if (INNER.has(key) && INNER.get(key)[0] === controller && INNER.get(key)[1] === endpoint.handler) policy = 'trusted-inner'
      else if (info?.permissions.length) policy = 'sa-token-permission'
      need(Boolean(policy), endpoint.file, `Unregistered permission/public/self/inner policy: ${key}`)
      need(!/@SaIgnore\b/.test((info?.annotations ?? '') + contents.get(endpoint.file).slice(0, contents.get(endpoint.file).search(/\bclass\s+/))), endpoint.file, `SaIgnore bypass needs review: ${key}`)
      endpointsEvidence.push({ ...endpoint, policy: policy ?? 'missing', permissions: info?.permissions ?? [] })
    }
    const security = read(`${SECURITY}/config/RobotSecurityAutoConfiguration.java`)
    for (const pattern of [/new SaInterceptor\(handler -> StpUtil\.checkLogin\(\)\)/,
      /new LoginSessionValidationInterceptor\(sessionStateVerifier\)/, /requiredSessionSubjectVerifier/]) {
      need(pattern.test(security), `${SECURITY}/config/RobotSecurityAutoConfiguration.java`, 'Default authenticated/session/subject verifier assembly missing')
    }
  })
  check('RBD_PUBLIC_PATHS', () => {
    const publicPaths = [...PUBLIC].map((key) => key.split(' ')[1]).sort()
    const allowedManagement = new Set(['/actuator/**', '/v3/api-docs/**', '/doc.html', '/webjars/**'])
    for (const file of [AUTH_YML, SYSTEM_YML]) {
      const paths = yamlList(read(file), 'exclude-paths')
      const business = paths.filter((path) => !allowedManagement.has(path))
      const expected = file === AUTH_YML ? [...publicPaths, '/auth/logout'].sort() : ['/inner/**']
      need(JSON.stringify([...new Set(business)].sort()) === JSON.stringify(expected), file, 'Service public paths differ from exact reviewed policy')
    }
    const paths = yamlList(read(GATEWAY_YML), 'white-paths')
    need(JSON.stringify(paths.filter((path) => !['/actuator/health', '/actuator/prometheus'].includes(path)).sort())
      === JSON.stringify(publicPaths), GATEWAY_YML, 'Gateway public paths differ from exact reviewed policy')
    const defaultsFile = 'robot-gateway/src/main/java/com/robot/gateway/properties/AuthProperties.java'
    const defaults = [...read(defaultsFile).matchAll(/"(\/[^"\n]+)"/g)].map((m) => m[1]).filter((path) => path.startsWith('/auth'))
    need(JSON.stringify(defaults.sort()) === JSON.stringify(publicPaths), defaultsFile, 'Gateway defaults have unreviewed public auth path')
  })
  check('RBD_INNER_TRUST', () => {
    const file = `${SECURITY}/config/RobotSecurityAutoConfiguration.java`, security = read(file)
    need(/new InnerCallInterceptor\(properties\)\)\s*\.addPathPatterns\("\/inner\/\*\*"\)/.test(security), file, 'Internal service interceptor registration missing')
    need(/new InnerTrustContextFilter\(\)/.test(security), file, 'Internal trust cleanup registration missing')
    const accessFile = `${SYSTEM}/security/InnerSystemAccess.java`, access = read(accessFile)
    need(methodInfo(access, 'requireUser')?.body.includes('AuthUtil.requireTrustedService()'), accessFile, 'Account supply lacks trusted-service requirement')
    need(methodInfo(access, 'requireActiveMembership')?.body.includes('AuthUtil.requireTrustedUser(userId, companyId)'), accessFile, 'Permission supply lacks delegated user/company requirement')
    for (const endpoint of endpoints.filter((candidate) => candidate.path.startsWith('/inner/'))) {
      const expected = INNER.get(`${endpoint.method} ${endpoint.path}`)
      need(Boolean(expected), endpoint.file, 'Unknown inner endpoint needs explicit trust policy')
      if (!expected) continue
      const [controller, handler, serviceMethod, guard] = expected
      need(basename(endpoint.file, '.java') === controller && endpoint.handler === handler, endpoint.file, 'Inner endpoint handler drift')
      const body = methodInfo(read(endpoint.file), handler)?.body ?? ''
      if (serviceMethod) {
        const serviceFile = serviceMethod === 'recordLogin' ? `${SYSTEM}/account/service/LoginLogService.java` : `${SYSTEM}/user/service/UserSupplyService.java`
        need(body.includes(`.${serviceMethod}(`), endpoint.file, 'Trusted supply call removed')
        const serviceBody = compact(methodInfo(read(serviceFile), serviceMethod)?.body ?? '')
        need(serviceBody.includes(compact(guard)), serviceFile, `Trusted guard missing from ${serviceMethod}`)
        // Guards must precede data access, not merely appear somewhere after it.
        const firstAccess = serviceBody.search(/(?:userMapper|userCompanyMapper|loginLogMapper)\./)
        need(firstAccess < 0 || serviceBody.indexOf(compact(guard)) < firstAccess, serviceFile, `Guard follows persistence in ${serviceMethod}`)
      } else need(compact(body).trim().startsWith(`${compact(guard)};`), endpoint.file, 'Delegated membership guard must precede query')
    }
    const authFile = `${AUTH}/service/AuthService.java`
    need(methodInfo(read(authFile), 'logout')?.body.includes('sessions.logoutCurrent()'), authFile, 'Public logout no longer enters family-owned revocation')
  })
  check('RBD_SOFT_DELETE', () => {
    const file = '.wl-skills-bd/rules.local.json', local = JSON.parse(read(file))
    need(local.softDelete?.activeValue === 1 && local.softDelete?.deletedValue === 0, file, 'Robot soft delete facts must remain active=1/deleted=0')
    const constants = read(`${CORE}/constant/RobotConstants.java`)
    need(/DELETE_FLAG_ACTIVE\s*=\s*1\s*;/.test(constants) && /DELETE_FLAG_DELETED\s*=\s*0\s*;/.test(constants), `${CORE}/constant/RobotConstants.java`, 'Java soft delete constants drift')
    const yaml = read(SYSTEM_YML)
    need(/logic-delete-field:\s*deleteFlag\s*\n/.test(yaml) && /logic-delete-value:\s*0\s*\n/.test(yaml)
      && /logic-not-delete-value:\s*1\s*\n/.test(yaml), SYSTEM_YML, 'MyBatis logical delete configuration drift')
  })
  check('RBD_TENANT', () => {
    const file = `${MYBATIS}/config/RobotMybatisAutoConfiguration.java`, source = read(file)
    const tenantIndex = compact(source).indexOf('interceptor.addInnerInterceptor(newTenantLineInnerInterceptor(')
    const paginationIndex = compact(source).indexOf('interceptor.addInnerInterceptor(pagination)')
    need(tenantIndex >= 0 && paginationIndex > tenantIndex && /if\s*\(tenantProperties\.isEnabled\(\)\)/.test(source), file, 'Tenant interceptor must precede pagination')
    const propertiesFile = `${MYBATIS}/properties/TenantProperties.java`
    need(/boolean enabled\s*=\s*true\s*;/.test(read(propertiesFile)), propertiesFile, 'Tenant interception default disabled')
    const handlerFile = `${MYBATIS}/tenant/RobotTenantLineHandler.java`, handler = read(handlerFile)
    need(/String companyId = AuthUtil\.getLoginCompanyId\(\)/.test(handler) && /return new NullValue\(\)/.test(handler), handlerFile, 'Tenant must come from trusted subject and fail closed')
    need(/return RobotConstants\.TENANT_COLUMN;/.test(handler), handlerFile, 'Tenant column source drift')
    need(/TENANT_COLUMN\s*=\s*"company_id"/.test(read(`${CORE}/constant/RobotConstants.java`)), `${CORE}/constant/RobotConstants.java`, 'Robot company tenant column drift')
    const yaml = read(SYSTEM_YML)
    need(/tenant:\s*\n\s+enabled:\s*true\s*\n/.test(yaml), SYSTEM_YML, 'System tenant interception is not explicitly enabled')
    const localFile = '.wl-skills-bd/rules.local.json', local = JSON.parse(read(localFile))
    need(local.tenant?.mode === 'interceptor' && local.tenant?.evidence === file, localFile, 'Native tenant evidence no longer points to actual Robot assembly')
  })
  check('RBD_PAGE_QUERY', () => {
    const file = `${CORE}/domain/PageQuery.java`, source = read(file)
    need(compact(methodInfo(source, 'pageValue')?.body ?? '').includes('page==null||page<1'), file, 'Page lower/null boundary missing')
    const size = compact(methodInfo(source, 'pageSizeValue')?.body ?? '')
    need(size.includes('pageSize==null||pageSize<1') && size.includes('Math.min(pageSize,RobotConstants.MAX_PAGE_SIZE)'), file, 'PageQuery no longer bounds client page size')
    need(compact(methodInfo(source, 'offset')?.body ?? '').includes('(long)(pageValue()-1)*pageSizeValue()'), file, 'Offset must widen before multiplication')
    need(/MAX_PAGE_SIZE\s*=\s*200\s*;/.test(read(`${CORE}/constant/RobotConstants.java`)), `${CORE}/constant/RobotConstants.java`, 'Robot page size limit drift')
  })
  check('RBD_HTTP_CONTRACTS', () => {
    const matched = new Set()
    for (const [file] of inputs) {
      if (!/^contracts\/system\/[^/]+\.json$/.test(file)) continue
      const contract = JSON.parse(read(file))
      need(contract.schemaVersion === 1, file, 'Unsupported Robot HTTP contract schema')
      if (contract.envelope) need(contract.envelope.successCode === 0 && JSON.stringify(contract.envelope.pageFields)
        === JSON.stringify(['list', 'total', 'page', 'pageSize']), file, 'Robot wire envelope differs from documented success/page fields')
      const routes = Array.isArray(contract.routes) ? contract.routes.map((route) => ({
        controller: contract.controller?.split('.').at(-1), methods: [route.method], paths: [route.path], permission: route.permission,
        response: route.response,
      })) : Object.entries(contract.routes ?? {}).map(([handler, route]) => ({
        controller: handler.split('.')[0], handler: handler.split('.')[1], methods: route.method, paths: route.paths,
        permission: route.permission, response: route.response,
      }))
      for (const route of routes) for (const method of route.methods ?? []) for (const path of route.paths ?? []) {
        const actual = endpointsEvidence.find((endpoint) => basename(endpoint.file, '.java') === route.controller
          && endpoint.method === method && endpoint.path === path && (!route.handler || endpoint.handler === route.handler))
        need(Boolean(actual), file, `Contract endpoint absent/drifted: ${route.controller} ${method} ${path}`)
        if (!actual) continue
        matched.add(`${actual.file}:${actual.handler}:${actual.method}:${actual.path}`)
        const permissions = route.permission == null ? [] : Array.isArray(route.permission) ? route.permission : [route.permission]
        need(JSON.stringify(permissions.slice().sort()) === JSON.stringify(actual.permissions), file, `Sa-Token contract permission mismatch: ${method} ${path}`)
        const info = methodInfo(contents.get(actual.file), actual.handler)
        need(simpleType(info?.returnType ?? '') === simpleType(route.response ?? ''), file, `Response type contract mismatch: ${method} ${path}`)
      }
    }
    for (const endpoint of endpointsEvidence.filter((endpoint) => endpoint.file.startsWith('robot-services/robot-system/'))) {
      need(matched.has(`${endpoint.file}:${endpoint.handler}:${endpoint.method}:${endpoint.path}`), endpoint.file, `System endpoint missing Robot HTTP contract: ${endpoint.method} ${endpoint.path}`)
    }
  })
  return { schemaVersion: 1, ok: findings.length === 0, checks, findings, checkedFiles: [...checkedFiles].sort(),
    endpointCount: endpoints.length, endpoints: endpointsEvidence,
    limitations: ['Static declarations and fixed policies are checked; effective Sa-Token/interceptor/SQL behavior needs formal Java tests.',
      'BD 0.37.0 has no Robot Sa-Token semantic extension API; native B1 results remain unchanged.',
      'These RBD checks do not replace B1 or report Java J1–J8 as passed.',
      'DTO serialization/validation and schema migration behavior remain governed by Robot machine contracts and Java/MySQL suites.'] }
}
