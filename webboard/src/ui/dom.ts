/**
 * Minimal hand-rolled DOM component helpers — no UI framework.
 */

export type Child = Node | string | null | undefined | false;

export interface Attrs {
  class?: string;
  text?: string;
  title?: string;
  placeholder?: string;
  type?: string;
  value?: string;
  html?: string;
  tabindex?: string;
  onClick?: (ev: MouseEvent) => void;
  onInput?: (ev: Event) => void;
  onKeydown?: (ev: KeyboardEvent) => void;
  onMouseenter?: (ev: MouseEvent) => void;
  onMouseleave?: (ev: MouseEvent) => void;
  dataset?: Record<string, string>;
}

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Attrs = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (attrs.class) node.className = attrs.class;
  if (attrs.text !== undefined) node.textContent = attrs.text;
  if (attrs.html !== undefined) node.innerHTML = attrs.html;
  if (attrs.title) node.title = attrs.title;
  if (attrs.tabindex !== undefined) node.tabIndex = Number(attrs.tabindex);
  if (attrs.placeholder && node instanceof HTMLInputElement) node.placeholder = attrs.placeholder;
  if (attrs.type && node instanceof HTMLInputElement) node.type = attrs.type;
  if (attrs.value !== undefined && node instanceof HTMLInputElement) node.value = attrs.value;
  if (attrs.dataset) {
    for (const [k, v] of Object.entries(attrs.dataset)) node.dataset[k] = v;
  }
  if (attrs.onClick) node.addEventListener('click', attrs.onClick as EventListener);
  if (attrs.onInput) node.addEventListener('input', attrs.onInput);
  if (attrs.onKeydown) node.addEventListener('keydown', attrs.onKeydown as EventListener);
  if (attrs.onMouseenter) node.addEventListener('mouseenter', attrs.onMouseenter as EventListener);
  if (attrs.onMouseleave) node.addEventListener('mouseleave', attrs.onMouseleave as EventListener);
  append(node, ...children);
  return node;
}

export function append(parent: Element, ...children: Child[]): void {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    parent.append(c);
  }
}

export function clear(node: Element): void {
  node.replaceChildren();
}

/** Copies text to the clipboard, with an execCommand fallback for http origins. */
export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }
}
