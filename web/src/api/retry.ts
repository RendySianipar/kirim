/**
 * Retries a request with exponential backoff.
 *
 * Retrying a POST is only safe because the caller reuses the SAME
 * Idempotency-Key on every attempt, so the server runs the transfer at most once.
 */
export interface RetryOptions {
  retries?: number
  baseDelayMs?: number
  sleep?: (ms: number) => Promise<void>
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** 5xx = server trouble, 409 = same key still in progress, 429 = rate limited. */
export const isRetryable = (status: number) => status >= 500 || status === 409 || status === 429

export async function sendWithRetry(
  request: () => Promise<Response>,
  { retries = 3, baseDelayMs = 300, sleep = defaultSleep }: RetryOptions = {},
): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const isLast = attempt === retries
    try {
      const response = await request()
      if (!isRetryable(response.status) || isLast) return response
    } catch (error) {
      // fetch rejects with a TypeError on network failure (offline, timeout, reset).
      if (isLast) throw error
    }
    await sleep(baseDelayMs * 2 ** attempt)
  }
}
