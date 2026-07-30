/**
 * Decision cards: the build's pending decisions as clickable
 * cards. An E1 ambiguity ("UserRepository has 2 implementations") renders with
 * one button per candidate; a click asks the board server to write the
 * corresponding `@Bind` line into the module's GraphRules.kt. The card stays in
 * an "applied — rebuild" state until the next successful build clears it from
 * decisions.json.
 */

import type { DecisionsFile, PendingDecision } from '../model/decisions';
import { clear, el } from './dom';

export interface DecisionCardsCallbacks {
  /** Resolves with the server's answer, e.g. `{ file, line }`; rejects on failure. */
  onApply: (decision: PendingDecision, candidateFqn: string) => Promise<{ file: string; line: number }>;
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

    clear(this.root);
    if (file.pending.length === 0) {
      this.root.classList.add('hidden');
      return;
    }
    this.root.classList.remove('hidden');
    this.root.append(
      el('div', { class: 'decision-cards-title', text: `build blocked — ${file.pending.length} decision${file.pending.length > 1 ? 's' : ''} needed` }),
    );
    for (const decision of file.pending) {
      this.root.append(this.renderCard(decision));
    }
  }

  private renderCard(decision: PendingDecision): HTMLElement {
    const card = el('div', { class: 'decision-card' }, el('div', { class: 'decision-title', text: decision.title }));
    for (const consumer of decision.consumers) {
      card.append(el('div', { class: 'decision-consumer muted mono', text: `→ ${consumer}` }));
    }

    const note = this.applied.get(decision.id);
    if (note !== undefined) {
      card.append(el('div', { class: 'decision-applied', text: note }));
      return card;
    }

    const buttons = el('div', { class: 'decision-buttons' });
    for (const candidate of decision.candidates) {
      buttons.append(
        el('button', {
          class: 'decision-btn',
          text: candidate.displayName,
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
