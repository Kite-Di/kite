/**
 * Toasts + banners:
 *  - change summary toasts ("Build #abc123 · +2 nodes · +3 edges · 1 changed")
 *    that fly the camera to the change bounding box on click
 *  - persistent amber "app disconnected" banner
 *  - "events dropped / resynced" indicator
 */

import { el } from './dom';

export type ToastKind = 'info' | 'success' | 'warn' | 'error';

export interface ToastOpts {
  kind?: ToastKind;
  ttlMs?: number;
  onClick?: () => void;
}

export class Toasts {
  private readonly stack: HTMLElement;
  private readonly bannerEl: HTMLElement;

  constructor(parent: HTMLElement) {
    this.bannerEl = el('div', { class: 'banner hidden' });
    this.stack = el('div', { class: 'toast-stack' });
    parent.append(this.bannerEl, this.stack);
  }

  show(text: string, opts: ToastOpts = {}): void {
    const kind = opts.kind ?? 'info';
    const toast = el(
      'div',
      {
        class: `toast toast-${kind}${opts.onClick ? ' clickable' : ''}`,
        onClick: () => {
          opts.onClick?.();
          dismiss();
        },
      },
      el('span', { class: 'toast-dot' }),
      el('span', { class: 'toast-text', text }),
    );
    this.stack.append(toast);
    requestAnimationFrame(() => toast.classList.add('visible'));

    let dismissed = false;
    const dismiss = () => {
      if (dismissed) return;
      dismissed = true;
      toast.classList.remove('visible');
      setTimeout(() => toast.remove(), 250);
    };
    setTimeout(dismiss, opts.ttlMs ?? 5000);
  }

  /** Persistent banner; pass null to hide. */
  banner(text: string | null, kind: ToastKind = 'warn'): void {
    if (text === null) {
      this.bannerEl.classList.add('hidden');
      return;
    }
    this.bannerEl.className = `banner banner-${kind}`;
    this.bannerEl.textContent = text;
  }
}
