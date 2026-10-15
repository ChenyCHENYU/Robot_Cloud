// Validates Git history with the same config as the commit-msg hook.
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import load from '@commitlint/load'
import lint from '@commitlint/lint'

export const PROJECT_ROOT = fileURLToPath(new URL('../', import.meta.url))

export async function checkCommits({ cwd = PROJECT_ROOT, range, historicalHead } = {}) {
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8' }).trim()
  const policy = JSON.parse(readFileSync(resolve(PROJECT_ROOT, '.commit-policy.json'), 'utf8'))
  const boundary = historicalHead ?? policy.historicalHead
  if (!/^[a-f0-9]{40}$/.test(boundary)) throw new Error('historicalHead must be a full Git SHA')
  git('cat-file', '-e', `${boundary}^{commit}`)
  const selection = range ?? `${boundary}..HEAD`
  if (!/^[A-Za-z0-9_./~^{}@:+-]+$/.test(selection) || selection.startsWith('-')) {
    throw new Error('invalid Git range')
  }
  const config = await load({}, { cwd: PROJECT_ROOT })
  const commitShas = git('rev-list', '--reverse', selection).split('\n').filter(Boolean)
  const results = []
  for (const sha of commitShas) {
    const message = git('show', '-s', '--format=%B', sha)
    const parents = git('show', '-s', '--format=%P', sha).split(' ').filter(Boolean)
    const result = await lint(message, config.rules, {
      parserOpts: config.parserPreset?.parserOpts,
      defaultIgnores: config.defaultIgnores,
      ignores: config.ignores,
    })
    const historyErrors = parents.length > 1
      ? [{ name: 'linear-history', level: 2, message: 'new commits must have at most one parent; integrate with --ff-only' }]
      : []
    results.push({ sha, parents: parents.length, header: message.split('\n')[0],
      valid: result.valid && historyErrors.length === 0, errors: [...result.errors, ...historyErrors] })
  }
  return { ok: results.every((result) => result.valid), range: selection, checked: results.length, commits: results }
}

export async function main(args = process.argv.slice(2)) {
  if (args.length !== 0 && !(args.length === 2 && args[0] === '--range')) {
    throw new Error('usage: bun run check:commits [--range <base>..HEAD]')
  }
  const result = await checkCommits({ range: args[1] })
  console.log(JSON.stringify(result, null, 2))
  return result.ok ? 0 : 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exitCode = await main() }
  catch (error) { console.error(error.message); process.exitCode = 1 }
}
