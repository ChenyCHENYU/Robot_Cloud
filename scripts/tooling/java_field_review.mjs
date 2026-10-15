// Conservative lexical proof for exact declarations; not a Java/runtime security analyzer.
export function javaTokens(source) {
  const tokens = []
  let i = 0, line = 1
  const step = () => { if (source[i] === '\n') line++; i++ }
  while (i < source.length) {
    if (/\s/.test(source[i])) { step(); continue }
    if (source.startsWith('//', i)) { while (i < source.length && source[i] !== '\n') step(); continue }
    if (source.startsWith('/*', i)) {
      step(); step()
      while (i < source.length && !source.startsWith('*/', i)) step()
      if (i === source.length) throw new Error('Unclosed Java comment')
      step(); step(); continue
    }
    const start = i, startLine = line
    if (source[i] === '"' || source[i] === "'") {
      const delimiter = source.startsWith('"""', i) ? '"""' : source[i]
      for (let n = 0; n < delimiter.length; n++) step()
      let closed = false
      while (i < source.length) {
        if (source[i] === '\\') { step(); if (i < source.length) step(); continue }
        if (source.startsWith(delimiter, i)) {
          for (let n = 0; n < delimiter.length; n++) step()
          closed = true; break
        }
        step()
      }
      if (!closed) throw new Error('Unclosed Java literal')
      tokens.push({ value: '<literal>', kind: 'literal', line: startLine, start }); continue
    }
    const name = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(source.slice(i))?.[0]
    if (name) { i += name.length; tokens.push({ value: name, kind: 'identifier', line: startLine, start }); continue }
    tokens.push({ value: source[i], kind: 'symbol', line: startLine, start }); step()
  }
  return tokens
}

function closing(tokens, start, open, close) {
  let depth = 0
  for (let i = start; i < tokens.length; i++) {
    if (tokens[i].value === open) depth++
    if (tokens[i].value === close && --depth === 0) return i
  }
  throw new Error(`Unclosed Java ${open}`)
}

function qualified(tokens, start) {
  let i = start
  const names = []
  if (tokens[i]?.kind !== 'identifier') throw new Error('Unsupported Java qualified name')
  names.push(tokens[i++].value)
  while (tokens[i]?.value === '.') {
    i++
    if (tokens[i]?.kind !== 'identifier') throw new Error('Unsupported Java qualified name')
    names.push(tokens[i++].value)
  }
  return { name: names.join('.'), end: i }
}

export function javaClass(source, expectedSimpleName) {
  const tokens = javaTokens(source), imports = new Map(), types = []
  let packageName = '', depth = 0, boundary = 0
  for (let i = 0; i < tokens.length; i++) {
    const value = tokens[i].value
    if (depth === 0 && value === 'package') {
      const parsed = qualified(tokens, i + 1)
      if (packageName || tokens[parsed.end]?.value !== ';') throw new Error('Ambiguous Java package')
      packageName = parsed.name
    }
    if (depth === 0 && value === 'import' && tokens[i + 1]?.value !== 'static') {
      let end = i + 1
      while (end < tokens.length && tokens[end].value !== ';') end++
      const name = tokens.slice(i + 1, end).map((token) => token.value).join('')
      if (!name.includes('*')) {
        const simple = name.split('.').at(-1)
        if (imports.has(simple) && imports.get(simple) !== name) throw new Error('Ambiguous Java import')
        imports.set(simple, name)
      }
    }
    if (depth === 0 && ['class', 'interface', 'enum', 'record'].includes(value)
        && tokens[i - 1]?.value !== '.' && tokens[i + 1]?.kind === 'identifier') {
      let open = i + 2
      while (open < tokens.length && tokens[open].value !== '{') open++
      if (open === tokens.length) throw new Error('Missing Java type body')
      const close = closing(tokens, open, '{', '}')
      const modifiers = tokens.slice(boundary, i).map((token) => token.value)
      types.push({ name: tokens[i + 1].value, kind: value, open, close,
        packagePrivate: !modifiers.some((modifier) => ['public', 'protected', 'private'].includes(modifier)) })
    }
    if (value === '{') depth++
    if (value === '}') { depth--; if (depth < 0) throw new Error('Unbalanced Java type'); if (!depth) boundary = i + 1 }
    if (!depth && value === ';') boundary = i + 1
  }
  if (depth) throw new Error('Unbalanced Java type')
  const matches = types.filter((type) => type.name === expectedSimpleName)
  if (matches.length !== 1) throw new Error('Exact top-level Java type absent or ambiguous')
  return { tokens, imports, packageName, types, ...matches[0] }
}

const MODIFIERS = new Set(['public', 'protected', 'private', 'static', 'final', 'transient', 'volatile'])
function declaration(tokens) {
  const annotations = []
  let i = 0
  while (i < tokens.length) {
    if (MODIFIERS.has(tokens[i].value)) { i++; continue }
    if (tokens[i].value !== '@') break
    const parsed = qualified(tokens, i + 1)
    i = parsed.end
    let emptyArguments = true
    if (tokens[i]?.value === '(') {
      const end = closing(tokens, i, '(', ')')
      emptyArguments = end === i + 1
      i = end + 1
    }
    annotations.push({ name: parsed.name, emptyArguments })
  }
  const remainder = tokens.slice(i)
  return { annotations, remainder }
}

function exactLombok(annotation, type) {
  if (!annotation.emptyArguments) return false
  if (annotation.name === 'lombok.ToString.Exclude') return !type.tokens.some((t, i) =>
    ['class', 'interface', 'enum', 'record'].includes(t.value) && type.tokens[i + 1]?.value === 'lombok')
  if (annotation.name === 'ToString.Exclude') return type.imports.get('ToString') === 'lombok.ToString'
    && !type.tokens.some((t, i) => ['class', 'interface', 'enum', 'record'].includes(t.value)
      && type.tokens[i + 1]?.value === 'ToString')
  return annotation.name === 'Exclude' && type.imports.get('Exclude') === 'lombok.ToString.Exclude'
    && !type.tokens.some((t, i) => ['class', 'interface', 'enum', 'record'].includes(t.value)
      && type.tokens[i + 1]?.value === 'Exclude')
}

export function excludedField(source, { className, field, type: expectedType, line }) {
  const simple = className.split('.').at(-1), owner = javaClass(source, simple)
  if (`${owner.packageName}.${owner.name}` !== className || owner.kind !== 'class') return null
  const found = []
  let start = owner.open + 1, parentheses = 0
  for (let i = start; i < owner.close; i++) {
    const token = owner.tokens[i]
    if (token.value === '(') parentheses++
    if (token.value === ')') parentheses--
    if (parentheses < 0) throw new Error('Unbalanced member parentheses')
    if (token.value === '{') {
      const end = closing(owner.tokens, i, '{', '}')
      const header = owner.tokens.slice(start, i)
      if (!parentheses && !header.some((t) => t.value === '=')) {
        if (header.some((t, index) => t.value === 'toString' && header[index + 1]?.value === '(')) return null
        start = end + 1
      }
      i = end; continue
    }
    if (token.value !== ';' || parentheses) continue
    const parsed = declaration(owner.tokens.slice(start, i))
    const equals = parsed.remainder.findIndex((t) => t.value === '=')
    const left = equals < 0 ? parsed.remainder : parsed.remainder.slice(0, equals)
    if (left.length === 2 && left[0].value === expectedType && left[1].value === field
        && left[1].line === line && parsed.annotations.some((annotation) => exactLombok(annotation, owner))) {
      found.push({ className, field, type: expectedType, line: left[1].line,
        annotationNames: parsed.annotations.map((annotation) => annotation.name) })
    }
    start = i + 1
  }
  return found.length === 1 ? found[0] : null
}
