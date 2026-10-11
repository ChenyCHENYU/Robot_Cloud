// Same types and mandatory scope as Robot Admin. The backend owns this copy.
module.exports = {
  extends: ['@commitlint/config-conventional'],
  defaultIgnores: false,
  ignores: [(message) => /^Merge (?:branch|remote-tracking branch|pull request)\b/.test(message)],
  rules: {
    'type-enum': [2, 'always', [
      'wip', 'feat', 'fix', 'docs', 'style', 'refactor', 'perf',
      'test', 'chore', 'revert', 'build', 'deps',
    ]],
    'scope-empty': [2, 'never'],
    'subject-case': [0],
  },
}
