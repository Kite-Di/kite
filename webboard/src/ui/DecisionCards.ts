/**
 * Decision banners: the build's pending decisions as warning banners across the
 * top of the canvas. An E1 ambiguity ("UserRepository has 2 implementations")
 * renders with one button per candidate — the first is primary, as it is the one
 * the build error's hint suggests; a click asks the board server to write the
 * corresponding `@Bind` line into the module's GraphRules.kt. The banner stays in
 * an "applied — rebuild" state until the next successful build clears it from
 * decisions.json.
 */

import type { DecisionsFile, PendingDecision } from '../model/decisions';
import { clear, el } from './dom';
import { iconSvg } from './icons';

export interface DecisionCardsCallbacks {
  /** Resolves with the server's answer, e.g. `{ file, line }`; rejects on failure. */
  onApply: (decision: PendingDecision, candidateFqn: string) => Promise<{ file: string; line: number }>;
  /** The pending set changed — for the navbar badge and the rings on the canvas. */
  onChange: (pending: PendingDecision[]) => void;
}

export class DecisionCards {
  private readonly root: HTMLElement;
  /** decision id → "written to file:line" note, kept until the build clears the card. */
  private readonly applied = new Map<string, string>();
  private lastKey = '';

  constructor(parent: HTMLElement, private readonly cb: DecisionCardsCallbacks) {
    this.root = el('div', { class: 'decision-cards hidden' });
    parent.append(this.root);
  }

  setDecisions(file: DecisionsFile): void {
    // Re-render only on actual change — this is polled.
    const key = JSON.stringify(file.pending.map((p) => p.id + ':' + p.candidates.length));
    for (const id of [...this.applied.keys()]) {
      if (!file.pending.some((p) => p.id === id)) this.applied.delete(id); // build resolved it
    }
    if (key === this.lastKey && key !== '') return;
    this.lastKey = key;
    this.cb.onChange(file.pending);

    clear(this.root);
    if (file.pending.length === 0) {
      this.root.classList.add('hidden');
      return;
    }
    this.root.classList.remove('hidden');
    for (const decision of file.pending) {
      this.root.append(this.renderCard(decision));
    }
  }

  /** Draw the eye to the banners; false when there is nothing pending. */
  focus(): boolean {
    if (this.root.classList.contains('hidden')) return false;
    this.root.classList.remove('flash');
    void this.root.offsetWidth; // restart the animation
    this.root.classList.add('flash');
    return true;
  }

  private renderCard(decision: PendingDecision): HTMLElement {
    const text = el('div', { class: 'decision-text' }, el('b', { text: `Build blocked: ${decision.title}` }));
    for (const consumer of decision.consumers) {
      text.append(el('span', { class: 'decision-consumer mono', text: `→ ${consumer}`, title: consumer }));
    }
    const card = el('div', { class: 'decision-card' }, el('span', { class: 'decision-icon', html: iconSvg('warning') }), text);

    const note = this.applied.get(decision.id);
    if (note !== undefined) {
      card.append(el('div', { class: 'decision-applied', text: note }));
      return card;
    }

    const buttons = el('div', { class: 'decision-buttons' });
    for (const [i, candidate] of decision.candidates.entries()) {
      buttons.append(
        el('button', {
          class: i === 0 ? 'btn primary' : 'btn',
          text: `Bind ${candidate.displayName}`,
          title: `${candidate.fqn}\n${candidate.file}:${candidate.line}\nwrites: ${candidate.insert}`,
          onClick: (ev) => {
            const btn = ev.currentTarget as HTMLButtonElement;
            btn.disabled = true;
            this.cb
              .onApply(decision, candidate.fqn)
              .then(({ file, line }) => {
                this.applied.set(decision.id, `✓ wrote ${candidate.displayName} to ${file.split('/').pop()}:${line} — rebuild to apply`);
                this.lastKey = ''; // force re-render on next poll
                this.rerenderCard(card, decision);
              })
              .catch(() => {
                btn.disabled = false;
              });
          },
        }),
      );
    }
    card.append(buttons);
    return card;
  }

  private rerenderCard(card: HTMLElement, decision: PendingDecision): void {
    const fresh = this.renderCard(decision);
    card.replaceWith(fresh);
  }
}
