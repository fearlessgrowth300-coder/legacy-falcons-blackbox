// Keep secrets out of diagnostic output. Never record full URLs, bodies, or headers.
const endpointNames = new Set(['register', 'login', 'signup', 'integrity', 'gql', 'validate', 'token', 'authorize']);
export function summarizeUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || !(url.hostname === 'twitch.tv' || url.hostname.endsWith('.twitch.tv'))) return null;
    const last = url.pathname.split('/').filter(Boolean).at(-1)?.toLowerCase();
    return { origin: url.origin, endpoint: endpointNames.has(last) ? last : 'other' };
  } catch { return null; }
}
export function summarizeFailure(params) {
  return {
    error: /^net::ERR_[A-Z0-9_]+$/.test(params.errorText ?? '') ? params.errorText : 'network-error',
    blockedReason: /^[a-zA-Z-]{1,60}$/.test(params.blockedReason ?? '') ? params.blockedReason : undefined,
    canceled: Boolean(params.canceled)
  };
}
