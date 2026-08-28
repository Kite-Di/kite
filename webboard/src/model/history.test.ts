import { describe, expect, it } from 'vitest';
import { History } from './history';

const entry = (label: string) => ({ label, before: `${label}:before`, after: `${label}:after` });

describe('History', () => {
  it('walks back and forward over the same entries', () => {
    const h = new History<string>();
    h.push(entry('one'));
    h.push(entry('two'));

    expect(h.undo()?.label).toBe('two');
    expect(h.undo()?.label).toBe('one');
    expect(h.undo()).toBeNull();

    expect(h.redo()?.label).toBe('one');
    expect(h.redo()?.label).toBe('two');
    expect(h.redo()).toBeNull();
  });

  it('a new edit drops the redo branch', () => {
    const h = new History<string>();
    h.push(entry('one'));
    h.push(entry('two'));
    h.undo();
    expect(h.canRedo).toBe(true);

    h.push(entry('three'));
    expect(h.canRedo).toBe(false);
    expect(h.undo()?.label).toBe('three');
    expect(h.undo()?.label).toBe('one'); // 'two' is gone for good
  });

  it('reports what the shortcuts can do', () => {
    const h = new History<string>();
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(false);

    h.push(entry('one'));
    expect(h.canUndo).toBe(true);
    h.undo();
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(true);
  });

  it('drops the oldest entry past the limit', () => {
    const h = new History<string>(2);
    h.push(entry('one'));
    h.push(entry('two'));
    h.push(entry('three'));

    expect(h.depth).toBe(2);
    expect(h.undo()?.label).toBe('three');
    expect(h.undo()?.label).toBe('two');
    expect(h.undo()).toBeNull(); // 'one' fell off the bottom
  });

  it('clear forgets both directions', () => {
    const h = new History<string>();
    h.push(entry('one'));
    h.push(entry('two'));
    h.undo();

    h.clear();
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(false);
  });
});
