/**
 * @vitest-environment jsdom
 *
 * The flow editor's unsaved-work guard.
 *
 * The editor already tracked the dirty state and already printed an "unsaved changes" caption in
 * its topbar, and nothing whatsoever acted on it: the back arrow was a plain `<Link>` and there
 * was no `beforeunload` anywhere in the app. On a canvas where a flow is minutes of dragging nodes
 * around, one click discarded the graph without a word.
 *
 * The React Flow canvas is stubbed and the API client is stubbed; everything else is the real
 * component. What is under test is the editor's own decisions — when it blocks an exit, what it
 * offers, and what each of the three answers does — and none of those live inside the canvas.
 *
 * Every element is reached by `data-testid`, class or role, never by its caption. The captions are
 * pt-BR product copy: matching on them would make a wording change break the suite, and would put
 * pt-BR in a file the language guard checks.
 */
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const push = vi.fn();
const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
}));

// A plain anchor: `next/link` needs the App Router context, which does not exist outside a Next
// render. The guard is an `onClick` handler, and an anchor delivers that identically.
vi.mock('next/link', () => ({
  default: ({
    children,
    href,
    ...rest
  }: {
    children: React.ReactNode;
    href: string;
  } & React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

/**
 * React Flow renders a measured canvas and needs a ResizeObserver; neither has anything to say
 * about navigation guarding. `useNodesState` / `useEdgesState` are re-implemented on `useState`
 * because the editor's dirty tracking is defined in terms of the change callbacks they hand back.
 */
vi.mock('@xyflow/react', async () => {
  const { useCallback, useState } = await import('react');
  return {
    ReactFlow: () => <div data-testid="canvas" />,
    ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    Background: () => null,
    BackgroundVariant: { Dots: 'dots' },
    Controls: () => null,
    Handle: () => null,
    Position: { Left: 'left', Right: 'right', Top: 'top', Bottom: 'bottom' },
    addEdge: (connection: unknown, edges: unknown[]) => [...edges, connection],
    useNodesState: <T,>(initial: T[]) => {
      const [nodes, setNodes] = useState<T[]>(initial);
      const onChange = useCallback(() => undefined, []);
      return [nodes, setNodes, onChange];
    },
    useEdgesState: <T,>(initial: T[]) => {
      const [edges, setEdges] = useState<T[]>(initial);
      const onChange = useCallback(() => undefined, []);
      return [edges, setEdges, onChange];
    },
  };
});

const getWorkflow = vi.fn();
const createWorkflow = vi.fn();
const updateWorkflow = vi.fn();
vi.mock('@/lib/api/client', async (importOriginal) => ({
  // `importOriginal` hands back the real module for everything not stubbed below, and its type
  // parameter is the only way to say so. An `import()` type is what the API expects here.
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports
  ...(await importOriginal<typeof import('@/lib/api/client')>()),
  getWorkflow: (...args: unknown[]) => getWorkflow(...args),
  createWorkflow: (...args: unknown[]) => createWorkflow(...args),
  updateWorkflow: (...args: unknown[]) => updateWorkflow(...args),
  deleteWorkflow: vi.fn(),
  listWorkflowExecutions: vi.fn(async () => []),
  testWorkflow: vi.fn(),
}));

import { FlowEditor } from '@/app/(app)/flows/flow-editor';

/** Renders a NEW flow — the path with no backend read, so the editor is interactive at once. */
function renderEditor(): HTMLElement {
  return render(<FlowEditor workflowId={null} />).container;
}

/** Any edit marks the document dirty; the name field is the one with no canvas in the way. */
function makeDirty(container: HTMLElement) {
  const nameInput = container.querySelector<HTMLInputElement>('.flows-name-input');
  if (!nameInput) throw new Error('flow name input not found');
  fireEvent.change(nameInput, { target: { value: 'x' } });
}

/**
 * Clicks the back arrow and answers whether the editor blocked the navigation.
 *
 * The listener on `document` runs after React's (which is bound to the render container, below
 * it), so it observes the editor's decision and only then cancels the event itself — otherwise
 * jsdom tries to follow the href and logs "navigation to another Document" over the results.
 */
function clickBack(container: HTMLElement): boolean {
  const link = container.querySelector<HTMLAnchorElement>('a.icon-btn[href="/flows"]');
  if (!link) throw new Error('back link not found');
  let prevented = false;
  document.addEventListener(
    'click',
    (event) => {
      prevented = event.defaultPrevented;
      event.preventDefault();
    },
    { once: true },
  );
  fireEvent.click(link);
  return prevented;
}

/** `dispatchEvent` answers `false` when a listener called `preventDefault`. */
function fireBeforeUnload(): boolean {
  return window.dispatchEvent(new Event('beforeunload', { cancelable: true }));
}

function guard(): HTMLElement | null {
  return screen.queryByTestId('unsaved-exit-guard');
}

beforeEach(() => {
  push.mockClear();
  replace.mockClear();
  getWorkflow.mockReset();
  createWorkflow.mockReset();
  updateWorkflow.mockReset();
});

afterEach(cleanup);

describe('leaving the flow editor with unsaved changes', () => {
  it('lets a clean document close without a prompt', () => {
    renderEditor();
    expect(fireBeforeUnload()).toBe(true);
  });

  it('blocks the tab from closing once the document is dirty', () => {
    const container = renderEditor();
    makeDirty(container);
    expect(fireBeforeUnload()).toBe(false);
  });

  it('unbinds the guard when the editor unmounts', () => {
    const container = renderEditor();
    makeDirty(container);
    cleanup();
    // A listener surviving the screen would prompt on every later navigation in the tab.
    expect(fireBeforeUnload()).toBe(true);
  });

  it('lets the back arrow navigate normally while there is nothing to lose', () => {
    const container = renderEditor();
    expect(clickBack(container)).toBe(false);
    expect(guard()).toBeNull();
  });

  it('intercepts the back arrow and offers the three ways out', () => {
    const container = renderEditor();
    makeDirty(container);

    expect(clickBack(container)).toBe(true);
    const bar = guard();
    expect(bar).not.toBeNull();
    expect(push).not.toHaveBeenCalled();
    expect(within(bar as HTMLElement).getAllByRole('button')).toHaveLength(3);
    expect(screen.getByTestId('unsaved-exit-save')).toBeTruthy();
    expect(screen.getByTestId('unsaved-exit-discard')).toBeTruthy();
    expect(screen.getByTestId('unsaved-exit-cancel')).toBeTruthy();
  });

  it('returns to the editor, still dirty, when the prompt is dismissed', () => {
    const container = renderEditor();
    makeDirty(container);
    clickBack(container);
    fireEvent.click(screen.getByTestId('unsaved-exit-cancel'));

    expect(guard()).toBeNull();
    expect(push).not.toHaveBeenCalled();
    // Dismissing the prompt must not have cleared the dirty flag: the work is still unsaved.
    expect(fireBeforeUnload()).toBe(false);
  });

  it('leaves and drops the guard when the work is discarded on purpose', () => {
    const container = renderEditor();
    makeDirty(container);
    clickBack(container);
    fireEvent.click(screen.getByTestId('unsaved-exit-discard'));

    expect(push).toHaveBeenCalledWith('/flows');
    // No longer dirty, so the browser prompt must not fire on the way out as well.
    expect(fireBeforeUnload()).toBe(true);
  });

  it('stays put when "save and leave" cannot save', async () => {
    const container = renderEditor();
    makeDirty(container);
    clickBack(container);
    // A brand-new flow has a trigger but no action, so validation refuses before any request.
    fireEvent.click(screen.getByTestId('unsaved-exit-save'));
    await Promise.resolve();

    expect(createWorkflow).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
    // Still dirty: leaving here is exactly the data loss the prompt exists to prevent.
    expect(fireBeforeUnload()).toBe(false);
  });

  it('leaves only after the save actually lands', async () => {
    // An EXISTING flow, because the point is a save that succeeds: a brand-new one would have to
    // be built up to validity through the canvas first, which is not what this test is about.
    getWorkflow.mockResolvedValue({
      id: 'wf-1',
      name: 'Flow one',
      active: true,
      definition: {
        nodes: [
          { id: 'n1', kind: 'trigger', type: 'meeting.analysis_completed' },
          { id: 'n2', kind: 'action', type: 'send_email', params: { to: 'someone@example.com' } },
        ],
        edges: [{ id: 'e1', source: 'n1', target: 'n2' }],
      },
    });
    updateWorkflow.mockResolvedValue({ id: 'wf-1', updatedAt: '2026-08-23T10:00:00Z' });

    const container = render(<FlowEditor workflowId="wf-1" />).container;
    await vi.waitFor(() => expect(container.querySelector('.flows-name-input')).not.toBeNull());
    makeDirty(container);

    expect(clickBack(container)).toBe(true);
    fireEvent.click(screen.getByTestId('unsaved-exit-save'));

    await vi.waitFor(() => expect(updateWorkflow).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/flows'));
    // The save cleared the dirty flag, so nothing prompts on the way out.
    expect(fireBeforeUnload()).toBe(true);
  });
});
