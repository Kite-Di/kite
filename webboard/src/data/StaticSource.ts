/**
 * Static mode data source: drag-and-drop anywhere on the page or
 * a file picker for a `graph.json` exported by the processor.
 */

import { parseSnapshot, type GraphSnapshot } from '../model/graph';

export interface StaticSourceCallbacks {
  onSnapshot: (snapshot: GraphSnapshot, fileName: string) => void;
  onError: (message: string) => void;
  /** Fired while a file is dragged over the window (for drop-target affordance). */
  onDragState?: (active: boolean) => void;
}

export class StaticSource {
  private input: HTMLInputElement;
  private dragDepth = 0;

  constructor(private readonly cb: StaticSourceCallbacks) {
    this.input = document.createElement('input');
    this.input.type = 'file';
    this.input.accept = '.json,application/json';
    this.input.style.display = 'none';
    document.body.appendChild(this.input);
    this.input.addEventListener('change', () => {
      const file = this.input.files?.[0];
      if (file) void this.readFile(file);
      this.input.value = '';
    });

    window.addEventListener('dragenter', (e) => {
      e.preventDefault();
      this.dragDepth++;
      this.cb.onDragState?.(true);
    });
    window.addEventListener('dragleave', (e) => {
      e.preventDefault();
      this.dragDepth = Math.max(0, this.dragDepth - 1);
      if (this.dragDepth === 0) this.cb.onDragState?.(false);
    });
    window.addEventListener('dragover', (e) => e.preventDefault());
    window.addEventListener('drop', (e) => {
      e.preventDefault();
      this.dragDepth = 0;
      this.cb.onDragState?.(false);
      const file = e.dataTransfer?.files?.[0];
      if (file) void this.readFile(file);
    });
  }

  /** Opens the OS file picker. */
  openPicker(): void {
    this.input.click();
  }

  private async readFile(file: File): Promise<void> {
    try {
      const text = await file.text();
      const snapshot = parseSnapshot(JSON.parse(text));
      this.cb.onSnapshot(snapshot, file.name);
    } catch (e) {
      this.cb.onError(e instanceof Error ? e.message : `could not read ${file.name}`);
    }
  }
}
