/**
 * The board's write path: a decision card click must produce
 * exactly the file a human would have written by hand.
 */

import { describe, expect, it } from 'vitest';
import { insertRule, newRulesFile } from './rulesEdit';

const HOLDER = `package com.app.di

import com.app.ui.FirstPresenter
import com.kite.di.rules.Root

@Root(FirstPresenter::class)
private object GraphRules
`;

const INSERT = '@Bind(com.app.data.UserRepository::class, to = com.app.data.NetworkUserRepository::class)';

describe('insertRule', () => {
  it('inserts the rule directly above the holder object and adds the import', () => {
    const { source, line } = insertRule(HOLDER, 'GraphRules', INSERT);
    const lines = source.split('\n');
    expect(lines[line - 1]).toBe(INSERT);
    expect(lines[line]).toBe('private object GraphRules');
    // Import lands after the last existing import.
    expect(source).toContain('import com.kite.di.rules.Root\nimport com.kite.di.rules.Bind');
    // The original rule is untouched.
    expect(source).toContain('@Root(FirstPresenter::class)');
  });

  it('does not duplicate an existing import', () => {
    const once = insertRule(HOLDER, 'GraphRules', INSERT).source;
    const twice = insertRule(once, 'GraphRules', '@Bind(com.app.A::class, to = com.app.B::class)').source;
    expect(twice.match(/import com\.kite\.di\.rules\.Bind/g)).toHaveLength(1);
  });

  it('keeps the holder object indentation', () => {
    const nested = 'package p\n\nclass Outer {\n    @Suppress("unused")\n    internal object Rules\n}\n';
    const { source } = insertRule(nested, 'Rules', INSERT);
    expect(source).toContain(`    ${INSERT}\n    internal object Rules`);
  });

  it('ignores commented-out object declarations', () => {
    const commented = `package p\n\n// private object GraphRules — old holder\nprivate object GraphRules\n`;
    const { source, line } = insertRule(commented, 'GraphRules', INSERT);
    expect(source.split('\n')[line]).toBe('private object GraphRules');
  });

  it('throws when the holder object is missing', () => {
    expect(() => insertRule('package p\n', 'GraphRules', INSERT)).toThrow(/GraphRules not found/);
  });
});

describe('newRulesFile', () => {
  it('creates a compilable holder with package, import and the rule', () => {
    const content = newRulesFile('com.app.di', INSERT);
    expect(content.startsWith('package com.app.di\n\nimport com.kite.di.rules.Bind\n')).toBe(true);
    expect(content).toContain(`${INSERT}\nprivate object GraphRules`);
    expect(content.endsWith('\n')).toBe(true);
  });

  it('omits the package line for the root package', () => {
    const content = newRulesFile('', INSERT);
    expect(content.startsWith('import ')).toBe(true);
  });
});
