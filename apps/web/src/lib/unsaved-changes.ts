/**
 * The browser-level half of "you have unsaved work".
 *
 * Nothing in this app guarded a close or a reload, and the screen where that costs the most is the
 * flow editor: laying out a graph of triggers, conditions and actions is minutes of dragging, the
 * editor already TRACKS the dirty state and already prints an "unsaved changes" caption in its
 * topbar, and closing the tab still threw the whole graph away without a word.
 *
 * `beforeunload` covers what the app cannot see — tab close, reload, typing another address.
 * In-app navigation is a different problem with a different answer (the router owns it, and the
 * dialog has to be the product's own), so the editor intercepts its own links; this module is
 * only the browser boundary.
 *
 * The listener asks a CALLBACK rather than taking a boolean, so the caller can register once and
 * let the answer change underneath it without unbinding and rebinding on every keystroke.
 */
export function installUnsavedChangesGuard(isDirty: () => boolean): () => void {
  if (typeof window === 'undefined') return () => undefined;

  const onBeforeUnload = (event: BeforeUnloadEvent): void => {
    if (!isDirty()) return;
    // Both lines are required and neither is redundant. `preventDefault()` is the modern signal;
    // `returnValue` is what older engines actually read, and one without the other silently does
    // nothing in some browsers. No custom message: every current browser shows its own text and
    // ignores whatever string is handed over.
    event.preventDefault();
    event.returnValue = '';
  };

  window.addEventListener('beforeunload', onBeforeUnload);
  return () => window.removeEventListener('beforeunload', onBeforeUnload);
}
