/**
 * Which API key belongs to which LLM provider.
 *
 * The control plane (ADR 0024) names the provider at RUNTIME — an operator rebinds a service
 * in the console and the next request goes somewhere else, with a different base URL. The key
 * has to follow that decision, and the one rule that cannot bend is that a key is only ever
 * sent to the provider it belongs to.
 *
 * Lives here rather than inside the route handler so it can be tested: it is a pure function
 * of the environment, and it is the piece that decides whether a secret leaves for the right
 * host.
 */

/** The environment variable holding the key for a provider, by convention. */
export function keyVariableFor(provider: string): string {
  return `LLM_KEY_${provider.toUpperCase()}`;
}

/**
 * Resolves the credential for {@link provider}, or `""` when this deployment holds none.
 *
 * Two sources, in order:
 *
 * 1. `LLM_KEY_<PROVIDER>` — the explicit per-provider key.
 * 2. `defaultKey` (`LLM_API_KEY`) — but ONLY when `provider` is `defaultProvider`
 *    (`LLM_PROVIDER`), because that key belongs to that provider and to no other.
 *
 * Step 2 used to be unconditional, and that is a credential disclosure rather than a
 * convenience. In production `chat` was bound to `deepseek/deepseek-v4-flash` while only an
 * OpenAI key was configured, so every message sent the OpenAI account key to
 * `api.deepseek.com`. The provider answered `401 Authentication Fails` and the chat was
 * down — but the failure is not the 401. The failure is that a working credential for one
 * vendor was transmitted to another, and being rejected there was luck.
 *
 * Returning `""` is meaningful: the caller answers `503` naming the provider it has no key
 * for, which is a message an operator can act on.
 */
export function resolveProviderKey(
  provider: string,
  opts: {
    env?: Record<string, string | undefined>;
    defaultProvider: string;
    defaultKey: string;
  },
): string {
  const env = opts.env ?? process.env;
  const explicit = env[keyVariableFor(provider)];
  if (explicit && explicit.trim()) return explicit.trim();

  const sameProvider = provider.trim().toLowerCase() === opts.defaultProvider.trim().toLowerCase();
  return sameProvider ? opts.defaultKey : "";
}
