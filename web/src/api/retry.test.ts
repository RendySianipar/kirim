import { describe, expect, it } from 'vitest'
import { isRetryable, sendWithRetry } from './retry'

const noSleep = () => Promise.resolve()
const res = (status: number) => new Response(null, { status })

describe('isRetryable', () => {
  it('retries server errors, conflicts and rate limits', () => {
    expect([500, 502, 503, 409, 429].every(isRetryable)).toBe(true)
  })

  it('does not retry client errors or successes', () => {
    expect([200, 201, 400, 404, 422].some(isRetryable)).toBe(false)
  })
})

describe('sendWithRetry', () => {
  it('returns the first non-retryable response', async () => {
    let calls = 0
    const response = await sendWithRetry(async () => { calls++; return res(201) }, { sleep: noSleep })

    expect(response.status).toBe(201)
    expect(calls).toBe(1)
  })

  it('retries network failures and 5xx, then succeeds', async () => {
    const outcomes: Array<() => Response> = [
      () => { throw new TypeError('Failed to fetch') },
      () => res(503),
      () => res(201),
    ]
    let calls = 0
    const response = await sendWithRetry(async () => outcomes[calls++](), { sleep: noSleep })

    expect(response.status).toBe(201)
    expect(calls).toBe(3)
  })

  it('backs off exponentially between attempts', async () => {
    const delays: number[] = []
    await sendWithRetry(async () => res(500), {
      retries: 3,
      baseDelayMs: 100,
      sleep: async (ms) => { delays.push(ms) },
    })

    expect(delays).toEqual([100, 200, 400])
  })

  it('gives back the last response once retries are used up', async () => {
    let calls = 0
    const response = await sendWithRetry(async () => { calls++; return res(500) }, { retries: 2, sleep: noSleep })

    expect(response.status).toBe(500)
    expect(calls).toBe(3)
  })

  it('rethrows the network error if every attempt fails', async () => {
    await expect(
      sendWithRetry(async () => { throw new TypeError('offline') }, { retries: 1, sleep: noSleep }),
    ).rejects.toThrow('offline')
  })
})
