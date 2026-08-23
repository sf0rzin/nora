/**
 * @vitest-environment jsdom
 *
 * The last hop of the landing's promise, and the chat's answer to a refusal.
 *
 * The landing invites a visitor to type a question into its hero composer and carries it as `?q=`
 * all the way through signup. This page read `?s=` and nothing else, so the parameter arrived here
 * and was dropped: the product asked somebody to start typing and then showed them an empty box.
 *
 * The other half is 429. `/api/chat` has a per-principal request budget and the semantic search
 * behind it answers `MEETING_RATE_LIMITED`; both used to be wrapped in "não consegui responder
 * agora (…)", which reports a working limit as a malfunction.
 *
 * The orb and the markdown renderer are stubbed — they bring a shader and a parser and neither has
 * anything to say about either behaviour. Elements are reached by role and by class, never by
 * caption: the captions are pt-BR product copy.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const replace = vi.fn();
const push = vi.fn();
let params = new URLSearchParams();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push, refresh: vi.fn() }),
  useSearchParams: () => params,
}));

vi.mock('@/components/brand/shader-orb', () => ({ ShaderOrb: () => null }));
vi.mock('react-markdown', () => ({
  default: ({ children }: { children: string }) => <div data-testid="answer">{children}</div>,
}));
vi.mock('remark-gfm', () => ({ default: () => undefined }));

const streamChat = vi.fn();
const createChatSession = vi.fn();
const appendChatMessage = vi.fn();
const getChatSession = vi.fn();
vi.mock('@/lib/api/client', async (importOriginal) => ({
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports
  ...(await importOriginal<typeof import('@/lib/api/client')>()),
  streamChat: (...a: unknown[]) => streamChat(...a),
  createChatSession: (...a: unknown[]) => createChatSession(...a),
  appendChatMessage: (...a: unknown[]) => appendChatMessage(...a),
  getChatSession: (...a: unknown[]) => getChatSession(...a),
}));

import ChatPage from '@/app/(app)/chat/page';
import { errorCopy } from '@/lib/strings';

function composer(): HTMLTextAreaElement {
  return document.querySelector('textarea') as HTMLTextAreaElement;
}

beforeEach(() => {
  params = new URLSearchParams();
  replace.mockReset();
  push.mockReset();
  streamChat.mockReset();
  createChatSession.mockReset().mockResolvedValue({ id: 's-1', title: 'Nova sessão' });
  appendChatMessage.mockReset().mockResolvedValue(undefined);
  getChatSession.mockReset().mockResolvedValue({ title: 'Sessão', messages: [] });
});

afterEach(cleanup);

describe('the question carried over from the landing', () => {
  it('seeds the composer from ?q= instead of dropping it', async () => {
    params = new URLSearchParams({ q: 'O que ficou pendente esta semana?' });
    render(<ChatPage />);

    await vi.waitFor(() => expect(composer()).not.toBeNull());
    expect(composer().value).toBe('O que ficou pendente esta semana?');
  });

  it('does not send it by itself — a link must not spend an LLM call on its own', async () => {
    params = new URLSearchParams({ q: 'Resuma minha última reunião' });
    render(<ChatPage />);

    await vi.waitFor(() => expect(composer().value).not.toBe(''));
    expect(streamChat).not.toHaveBeenCalled();
    expect(createChatSession).not.toHaveBeenCalled();
  });

  it('takes the parameter out of the URL, so a reload does not overwrite what was typed', async () => {
    params = new URLSearchParams({ q: 'Quais riscos apareceram?' });
    render(<ChatPage />);

    await vi.waitFor(() => expect(replace).toHaveBeenCalled());
    expect(replace.mock.calls[0][0]).toBe('/chat');
  });

  it('leaves an existing conversation alone: ?s= means they came back to a session', async () => {
    params = new URLSearchParams({ s: 'sess-9', q: 'pergunta antiga' });
    render(<ChatPage />);

    await vi.waitFor(() => expect(getChatSession).toHaveBeenCalledWith('sess-9'));
    expect(composer().value).toBe('');
    expect(replace.mock.calls[0][0]).toBe('/chat?s=sess-9');
  });

  it('does nothing at all without the parameter', async () => {
    render(<ChatPage />);

    await vi.waitFor(() => expect(composer()).not.toBeNull());
    expect(composer().value).toBe('');
    expect(replace).not.toHaveBeenCalled();
  });
});

describe('a chat turn the backend refused for budget reasons', () => {
  /** Types a question and sends it. */
  async function ask() {
    await vi.waitFor(() => expect(composer()).not.toBeNull());
    fireEvent.change(composer(), { target: { value: 'oi' } });
    fireEvent.submit(composer().closest('form') ?? composer());
    fireEvent.keyDown(composer(), { key: 'Enter' });
  }

  it('says the limit plainly, with no "algo falhou" wrapper around it', async () => {
    streamChat.mockResolvedValue({
      ok: false,
      status: 429,
      body: null,
      json: async () => ({}),
    });
    render(<ChatPage />);
    await ask();

    const answer = await vi.waitFor(() => screen.getByTestId('answer'));
    expect(answer.textContent).toBe(errorCopy.MEETING_RATE_LIMITED);
  });

  it('prefers the message the route sent, when it sent one', async () => {
    streamChat.mockResolvedValue({
      ok: false,
      status: 429,
      body: null,
      json: async () => ({ error: 'Muitas mensagens em pouco tempo.' }),
    });
    render(<ChatPage />);
    await ask();

    const answer = await vi.waitFor(() => screen.getByTestId('answer'));
    expect(answer.textContent).toBe('Muitas mensagens em pouco tempo.');
  });

  it('still wraps any other failure, which IS a malfunction', async () => {
    streamChat.mockResolvedValue({
      ok: false,
      status: 502,
      body: null,
      json: async () => ({ error: 'Provedor indisponível.' }),
    });
    render(<ChatPage />);
    await ask();

    const answer = await vi.waitFor(() => screen.getByTestId('answer'));
    expect(answer.textContent).toContain('Provedor indisponível.');
    expect(answer.textContent).not.toBe('Provedor indisponível.');
  });
});
