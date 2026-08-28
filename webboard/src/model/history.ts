/**
 * Bounded undo/redo stack. Knows nothing about the board: an
 * entry is a label plus a before/after pair, and the caller decides what a
 * "state" is and how to apply one. Pure and synchronous, so it is unit-tested
 * without a canvas.
 */

export interface HistoryEntry<T> {
  /** What the toast says: "move :core", "arrange". */
  label: string;
  before: T;
  after: T;
}

export const HISTORY_LIMIT = 50;

export class History<T> {
  private readonly past: HistoryEntry<T>[] = [];
  private readonly future: HistoryEntry<T>[] = [];

  constructor(private readonly limit: number = HISTORY_LIMIT) {}

  /** Records a completed edit. Like everywhere else, this drops the redo branch. */
  push(entry: HistoryEntry<T>): void {
    this.past.push(entry);
    if (this.past.length > this.limit) this.past.shift();
    this.future.length = 0;
  }

  /** The entry to revert (apply its `before`), or null. */
  undo(): HistoryEntry<T> | null {
    const entry = this.past.pop();
    if (!entry) return null;
    this.future.push(entry);
    return entry;
  }

  /** The entry to replay (apply its `after`), or null. */
  redo(): HistoryEntry<T> | null {
    const entry = this.future.pop();
    if (!entry) return null;
    this.past.push(entry);
    return entry;
  }

  get canUndo(): boolean {
    return this.past.length > 0;
  }

  get canRedo(): boolean {
    return this.future.length > 0;
  }

  get depth(): number {
    return this.past.length;
  }

  clear(): void {
    this.past.length = 0;
    this.future.length = 0;
  }
}
