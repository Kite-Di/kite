/**
 * Empty / onboarding / error states:
 *  - connect failure → full-screen hint: build once, the board picks the graph up
 *  - empty graph → onboarding card with a minimal @Injectable example
 *  - unknown schemaVersion → blocking error card
 *  - drag-over drop-target affordance
 */

import { SUPPORTED_SCHEMA_VERSIONS } from '../model/graph';
import { clear, copyText, el } from './dom';

export class Overlays {
  private readonly root: HTMLElement;
  private readonly dropHint: HTMLElement;

  constructor(
    parent: HTMLElement,
    private readonly onPickFile: () => void,
    private readonly onCopied: (what: string) => void,
  ) {
    this.root = el('div', { class: 'overlay hidden' });
    this.dropHint = el('div', { class: 'drop-hint hidden' }, el('div', { class: 'drop-hint-card', text: 'Drop graph.json to load' }));
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
    const pre = el('pre', { class: 'code-block' }, el('code', { text: code }));
    if (copyable) {
      pre.append(
        el('button', {
          class: 'copy-btn',
          text: 'copy',
          onClick: () => {
            void copyText(code).then((ok) => ok && this.onCopied(code));
          },
        }),
      );
    }
    return pre;
  }

  /** Full-screen connect hint — nothing has been built for this board to show yet. */
  showConnectHint(): void {
    clear(this.root);
    this.root.classList.remove('hidden');
    this.root.append(
      el(
        'div',
        { class: 'overlay-card' },
        el('div', { class: 'overlay-icon', text: '◉' }),
        el('h1', { text: 'Build the app once' }),
        el('p', { class: 'muted', text: 'The graph is written by the Kite processor on every debug build, and this board reads it from there:' }),
        this.codeBlock('./gradlew :app:assembleDebug'),
        el('p', { class: 'muted', text: 'For live runtime badges, add the inspector and run the app — the board finds the device itself, no port forwarding:' }),
        this.codeBlock('debugImplementation("com.kite.di:inspector")'),
        el('p', { class: 'muted or-sep', text: '— or —' }),
        el(
          'p',
          { class: 'muted' },
          el('span', { text: 'drop a ' }),
          el('code', { text: 'graph.json' }),
          el('span', { text: ' anywhere, or ' }),
          el('span', { class: 'link', text: 'browse for a file', onClick: () => this.onPickFile() }),
        ),
        el('p', { class: 'muted retry-note', text: 'retrying connection automatically…' }),
      ),
    );
  }

  /** Empty graph → onboarding card with a minimal example. */
  showEmptyGraph(): void {
    clear(this.root);
    this.root.classList.remove('hidden');
    this.root.append(
      el(
        'div',
        { class: 'overlay-card' },
        el('div', { class: 'overlay-icon', text: '∅' }),
        el('h1', { text: 'The graph is empty' }),
        el('p', { class: 'muted', text: 'Annotate a class and rebuild — it will appear here:' }),
        this.codeBlock(
          `@Injectable
class GreetingUseCase(
    private val repository: UserRepository,
)

class MainActivity : ComponentActivity() {
    @Inject lateinit var greeting: GreetingUseCase
}`,
          false,
        ),
      ),
    );
  }

  /** Blocking error card for an unknown snapshot schemaVersion. */
  showSchemaError(foundVersion: number | undefined): void {
    clear(this.root);
    this.root.classList.remove('hidden');
    this.root.append(
      el(
        'div',
        { class: 'overlay-card overlay-error' },
        el('div', { class: 'overlay-icon', text: '⚠' }),
        el('h1', { text: 'Unsupported snapshot schema' }),
        el('p', { class: 'muted', text: `The app sent schemaVersion ${foundVersion ?? '?'} but this board supports ${SUPPORTED_SCHEMA_VERSIONS.join(', ')}.` }),
        el('p', { class: 'muted', text: 'Update the web board (or the Kite library) so both sides speak the same schema.' }),
      ),
    );
  }
}
