/**
 * The board's write path: pure text edits that turn a clicked
 * decision card into a line in the module's GraphRules.kt. Used by
 * board/server.ts (the only place with filesystem access); pure functions so
 * the edit logic is unit-testable.
 */

const RULES_PACKAGE = 'com.kite.di.rules';

export interface RuleInsertion {
  /** The full new file content. */
  source: string;
  /** 1-based line the rule landed on (for the "written to file:line" toast). */
  line: number;
}

/** `@Bind(...)` → `Bind` — the annotation the import must cover. */
function annotationName(insertLine: string): string | null {
  const m = /^@(\w+)/.exec(insertLine.trim());
  return m ? m[1]! : null;
}

/**
 * Inserts `insertLine` directly above the `object holderObject` declaration
 * (after any annotations already stacked on it — annotation order is not
 * semantic) and ensures the annotation's import exists. Throws when the holder
 * object cannot be found — the server reports that as an apply error.
 */
export function insertRule(source: string, holderObject: string, insertLine: string): RuleInsertion {
  const lines = source.split('\n');
  const objectRe = new RegExp(`(^|\\s)object\\s+${holderObject}\\b`);
  const objectIdx = lines.findIndex((l) => objectRe.test(l) && !l.trimStart().startsWith('//'));
  if (objectIdx < 0) {
    throw new Error(`object ${holderObject} not found in the rules file`);
  }
  const indent = /^\s*/.exec(lines[objectIdx]!)![0];
  lines.splice(objectIdx, 0, indent + insertLine.trim());
  let insertedAt = objectIdx;

  const ann = annotationName(insertLine);
  if (ann !== null) {
    const importLine = `import ${RULES_PACKAGE}.${ann}`;
    if (!lines.some((l) => l.trim() === importLine)) {
      // After the last import, else after the package line.
      let lastImport = -1;
      let packageIdx = -1;
      for (let i = 0; i < lines.length; i++) {
        const t = lines[i]!.trim();
        if (t.startsWith('import ')) lastImport = i;
        else if (t.startsWith('package ')) packageIdx = i;
      }
      if (lastImport >= 0) {
        lines.splice(lastImport + 1, 0, importLine);
        insertedAt += 1;
      } else if (packageIdx >= 0) {
        lines.splice(packageIdx + 1, 0, '', importLine);
        insertedAt += 2;
      } else {
        lines.unshift(importLine);
        insertedAt += 1;
      }
    }
  }
  return { source: lines.join('\n'), line: insertedAt + 1 };
}

/** A fresh GraphRules.kt for a module that has no holder object yet. */
export function newRulesFile(pkg: string, insertLine: string): string {
  const ann = annotationName(insertLine);
  const importBlock = ann !== null ? `import ${RULES_PACKAGE}.${ann}\n\n` : '';
  const packageBlock = pkg !== '' ? `package ${pkg}\n\n` : '';
  return (
    packageBlock +
    importBlock +
    '// Graph decisions — the few facts the code can\'t express.\n' +
    '// Started by the dependency board\'s decision cards; edit freely, it is ordinary Kotlin.\n' +
    `${insertLine.trim()}\n` +
    'private object GraphRules\n'
  );
}
