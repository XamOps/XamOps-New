import { createContext } from 'react';

/**
 * The Escape stack of a datastore drawer.
 *
 * Layers that Escape should close before the drawer itself (side panels, open popovers) register an
 * entry while they are open. The drawer's `window` keydown listener closes the topmost entry, wherever
 * focus is, and only closes the drawer once the stack is empty. The provider (`drawer/EscapeStack.tsx`)
 * focuses `returnFocus` after closing an entry when that element is still in the document.
 *
 * Outside a drawer there is no provider and the context is null.
 */
export interface EscapeEntry {
  close: () => void;
  /** The element that opened the layer, focused again once it closes. */
  returnFocus: HTMLElement | null;
}

export interface EscapeStackApi {
  /** Pushes an entry and returns the function that removes it. */
  register(entry: EscapeEntry): () => void;
}

export const EscapeStackContext = createContext<EscapeStackApi | null>(null);
