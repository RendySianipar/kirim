import { sendWithRetry } from './retry'

export interface Account {
  id: string
  ownerName: string
  currency: string
  balance: number
  createdAt: string
}

export interface LedgerEntry {
  id: string
  transferId: string | null
  type: 'DEBIT' | 'CREDIT'
  amount: number
  balanceAfter: number
  createdAt: string
}

export interface Transfer {
  id: string
  fromAccountId: string
  toAccountId: string
  amount: number
  currency: string
  description: string | null
  status: 'COMPLETED' | 'REJECTED'
  failureReason: string | null
  createdAt: string
}

export interface Notification {
  id: string
  message: string
  createdAt: string
}

export interface TransferInput {
  fromAccountId: string
  toAccountId: string
  amount: number
  description?: string
}

export interface TransferOutcome {
  transfer: Transfer
  replayed: boolean
  idempotencyKey: string
}

/** Server errors arrive as RFC 9457 problem details. */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function parse<T>(response: Response): Promise<T> {
  if (response.ok) return (await response.json()) as T
  let detail = response.statusText
  let code: string | undefined
  try {
    const problem = await response.json()
    detail = problem.detail ?? detail
    code = problem.code
  } catch {
    // not JSON; keep statusText
  }
  throw new ApiError(response.status, detail, code)
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const api = {
  listAccounts: () => fetch('/api/v1/accounts').then((r) => parse<Account[]>(r)),

  openAccount: (ownerName: string, openingBalance: number) =>
    fetch('/api/v1/accounts', json({ ownerName, openingBalance, currency: 'IDR' })).then((r) => parse<Account>(r)),

  entries: (accountId: string) =>
    fetch(`/api/v1/accounts/${accountId}/entries?limit=10`).then((r) => parse<LedgerEntry[]>(r)),

  notifications: (accountId: string) =>
    fetch(`/api/v1/accounts/${accountId}/notifications`).then((r) => parse<Notification[]>(r)),

  /**
   * One key per user intent: generated once, reused across every retry.
   * Pass a key explicitly to simulate a double-tap or a client retry.
   */
  async transfer(input: TransferInput, idempotencyKey: string = crypto.randomUUID()): Promise<TransferOutcome> {
    const response = await sendWithRetry(() =>
      fetch('/api/v1/transfers', {
        ...json({ ...input, currency: 'IDR' }),
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      }),
    )
    const transfer = await parse<Transfer>(response)
    return { transfer, replayed: response.headers.get('Idempotent-Replayed') === 'true', idempotencyKey }
  },
}

export const formatIdr = (amount: number) =>
  new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(amount)
