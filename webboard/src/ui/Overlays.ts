/**
 * Empty / onboarding / error states:
 *  - connect failure → full-screen hint: build once, the board picks the graph up
 *  - empty graph → onboarding card with a minimal inferred-graph example
 *  - unknown schemaVersion → blocking error card
 *  - drag-over drop-target affordance
 */

import mascot from '../assets/mascot.png';
import { SUPPORTED_SCHEMA_VERSIONS } from '../model/graph';
import { clear, copyText, el, type Child } from './dom';
import { iconSvg } from './icons';

export class Overlays {
  private readonly root: HTMLElement;
  private readonly dropHint: HTMLElement;

  constructor(
    parent: HTMLElement,
    private readonly onPickFile: () => void,
    private readonly onCopied: (what: string) => void,
  ) {
    this.root = el('div', { class: 'overlay hidden' });
    this.dropHint = el('div', { class: 'drop-hint hidden' }, el('div', { class: 'drop-hint-card', text: 'Drop graph.json to load it' }));
    parent.append(this.root, this.dropHint);
  }

  hide(): void {
    this.root.classList.add('hidden');
    clear(this.root);
  }

  setDropActive(active: boolean): void {
    this.dropHint.classList.toggle('hidden', !active);
  }

  private codeBlock(code: string, copyable = true): HTMLElement {
    const block = el('div', { class: 'code-block' }, el('pre', {}, el('code', { text: code })));
    if (copyable) {
      block.append(
        el('button', {
          class: 'btn sm',
          text: 'Copy',
          onClick: () => {
            void copyText(code).then((ok) => ok && this.onCopied(code));
          },
        }),
      );
    }
    return block;
  }

  /** The intro card: the kite on the left, the story on the right. */
  private card(title: string, ...body: Child[]): void {
    clear(this.root);
    this.root.classList.remove('hidden');
    const art = document.createElement('img');
    art.className = 'intro-art';
    art.src = mascot;
    art.alt = 'The Kite mascot holding a small dependency graph';
    this.root.append(el('div', { class: 'intro-card' }, art, el('div', { class: 'intro-body' }, el('h2', { text: title }), ...body)));
  }

  /** Nothing has been built for this board to show yet. */
  showConnectHint(): void {
    this.card(
      'Waiting for the first build',
      el('p', { text: 'Every debug build writes the graph, and this page reads it from there. Build the app once and it fills in, then updates after each build.' }),
      this.codeBlock('./gradlew :app:assembleDebug'),
      el('p', { text: 'To see what exists on a device right now, add the inspector — the same version as the Kite plugin — and run the app:' }),
      this.codeBlock('debugImplementation("com.kitedi:inspector:<version>")\nreleaseImplementation("com.kitedi:inspector-noop:<version>")'),
      el(
        'div',
        { class: 'intro-actions' },
        el('button', { class: 'btn', html: `${iconSvg('folder')}<span>Open graph.json</span>`, onClick: () => this.onPickFile() }),
        el('span', { class: 'quiet', text: 'or drop one anywhere · checking for a build every few seconds' }),
      ),
    );
  }

  /** The build ran and found nothing to inject. */
  showEmptyGraph(): void {
    this.card(
      'The graph is empty',
      el('p', { text: 'Implement an interface and rebuild. No annotations needed; it shows up here:' }),
      this.codeBlock(
        `interface UserRepository { fun userName(): String }

class RealUserRepository(private val api: Api) : UserRepository { … }

class MainActivity : ComponentActivity() {
    private val repository by userRepository()   // generated
}`,
        false,
      ),
    );
  }

  /** The app speaks a snapshot schema this board does not. */
  showSchemaError(foundVersion: number | undefined): void {
    this.card(
      'This board can’t read the graph',
      el('p', { text: `The app sent schema version ${foundVersion ?? '?'}; this board reads ${SUPPORTED_SCHEMA_VERSIONS.join(', ')}.` }),
      el('p', { text: 'Use the same Kite version for the plugin and the board, then rebuild.' }),
    );
  }
}
