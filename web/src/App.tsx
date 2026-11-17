import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, ApiError, formatIdr, type Account, type LedgerEntry, type Notification, type TransferOutcome } from './api/client'

interface LogLine {
  id: number
  outcome?: TransferOutcome
  error?: string
  label: string
}

let logId = 0

export default function App() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [log, setLog] = useState<LogLine[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const list = await api.listAccounts()
      setAccounts(list)
      setLoadError(null)
      setSelected((current) => current ?? list[0]?.id ?? null)
    } catch {
      setLoadError('Cannot reach the API. Is the backend running on :8080?')
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const record = (line: Omit<LogLine, 'id'>) => setLog((prev) => [{ id: ++logId, ...line }, ...prev].slice(0, 12))

  return (
    <div className="page">
      <header>
        <h1>Kirim</h1>
        <p>Transfers with idempotency keys, a transactional outbox and Kafka events.</p>
      </header>

      {loadError && <div className="banner">{loadError}</div>}

      <main>
        <section className="card">
          <h2>Accounts</h2>
          <ul className="accounts">
            {accounts.map((a) => (
              <li key={a.id}>
                <button className={a.id === selected ? 'account active' : 'account'} onClick={() => setSelected(a.id)}>
                  <span>{a.ownerName}</span>
                  <strong>{formatIdr(a.balance)}</strong>
                </button>
              </li>
            ))}
            {accounts.length === 0 && !loadError && <li className="muted">No accounts yet. Open two to get started.</li>}
          </ul>
          <OpenAccountForm onOpened={refresh} />
        </section>

        <section className="card">
          <h2>Send money</h2>
          <TransferForm accounts={accounts} onDone={refresh} record={record} />
          <h3>Responses</h3>
          <ul className="log">
            {log.map((line) => (
              <li key={line.id}>
                <span className="muted">{line.label}</span>{' '}
                {line.outcome ? <OutcomeView outcome={line.outcome} /> : <span className="bad">{line.error}</span>}
              </li>
            ))}
            {log.length === 0 && <li className="muted">Try "Double-tap": two requests, one key, one transfer.</li>}
          </ul>
        </section>

        {selected && <AccountActivity accountId={selected} key={selected} refreshSignal={accounts} />}
      </main>
    </div>
  )
}

function OutcomeView({ outcome }: { outcome: TransferOutcome }) {
  const { transfer, replayed } = outcome
  return (
    <>
      <span className={transfer.status === 'COMPLETED' ? 'good' : 'bad'}>
        {transfer.status}
        {transfer.failureReason ? ` · ${transfer.failureReason}` : ''}
      </span>{' '}
      {formatIdr(transfer.amount)} <code title={transfer.id}>#{transfer.id.slice(0, 8)}</code>
      {replayed && <span className="pill">replayed</span>}
    </>
  )
}

function OpenAccountForm({ onOpened }: { onOpened: () => void }) {
  const [name, setName] = useState('')
  const [deposit, setDeposit] = useState('1000000')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    await api.openAccount(name.trim(), Number(deposit) || 0)
    setName('')
    onOpened()
  }

  return (
    <form className="inline" onSubmit={submit}>
      <input placeholder="Owner name" value={name} onChange={(e) => setName(e.target.value)} />
      <input type="number" min={0} value={deposit} onChange={(e) => setDeposit(e.target.value)} aria-label="Opening balance" />
      <button type="submit">Open</button>
    </form>
  )
}

function TransferForm({
  accounts,
  onDone,
  record,
}: {
  accounts: Account[]
  onDone: () => void
  record: (line: Omit<LogLine, 'id'>) => void
}) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [amount, setAmount] = useState('50000')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)

  const fromId = from || accounts[0]?.id || ''
  const toId = to || accounts[1]?.id || ''
  const input = { fromAccountId: fromId, toAccountId: toId, amount: Number(amount), description: description || undefined }

  const run = async (label: string, calls: () => Promise<TransferOutcome>[]) => {
    setBusy(true)
    const results = await Promise.allSettled(calls())
    results.forEach((r, i) =>
      record(
        r.status === 'fulfilled'
          ? { label: `${label}${results.length > 1 ? ` #${i + 1}` : ''}`, outcome: r.value }
          : { label, error: r.reason instanceof ApiError ? `${r.reason.status} ${r.reason.message}` : String(r.reason) },
      ),
    )
    setBusy(false)
    onDone()
  }

  const send = (e: FormEvent) => {
    e.preventDefault()
    void run('Send', () => [api.transfer(input)])
  }

  /** Same key, fired twice at once: what a double-tap or a client retry looks like to the server. */
  const doubleTap = () => {
    const key = crypto.randomUUID()
    void run('Double-tap', () => [api.transfer(input, key), api.transfer(input, key)])
  }

  return (
    <form className="stack" onSubmit={send}>
      <label>
        From
        <select value={fromId} onChange={(e) => setFrom(e.target.value)}>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.ownerName}</option>)}
        </select>
      </label>
      <label>
        To
        <select value={toId} onChange={(e) => setTo(e.target.value)}>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.ownerName}</option>)}
        </select>
      </label>
      <label>
        Amount (IDR)
        <input type="number" min={1} value={amount} onChange={(e) => setAmount(e.target.value)} />
      </label>
      <label>
        Note
        <input maxLength={140} value={description} onChange={(e) => setDescription(e.target.value)} placeholder="optional" />
      </label>
      <div className="row">
        <button type="submit" disabled={busy || accounts.length < 2}>Send</button>
        <button type="button" className="secondary" disabled={busy || accounts.length < 2} onClick={doubleTap}>
          Double-tap
        </button>
      </div>
    </form>
  )
}

/** Notifications arrive asynchronously via Kafka, so we poll to show eventual consistency. */
function AccountActivity({ accountId, refreshSignal }: { accountId: string; refreshSignal: unknown }) {
  const [entries, setEntries] = useState<LedgerEntry[]>([])
  const [notes, setNotes] = useState<Notification[]>([])

  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const [e, n] = await Promise.all([api.entries(accountId), api.notifications(accountId)])
        if (alive) {
          setEntries(e)
          setNotes(n)
        }
      } catch {
        // keep the last good data; the banner above reports connectivity
      }
    }
    void load()
    const timer = setInterval(load, 2000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [accountId, refreshSignal])

  return (
    <section className="card wide">
      <div className="columns">
        <div>
          <h2>Statement</h2>
          <table>
            <tbody>
              {entries.map((e) => (
                <tr key={e.id}>
                  <td className={e.type === 'CREDIT' ? 'good' : 'bad'}>{e.type === 'CREDIT' ? '+' : '−'}{formatIdr(e.amount)}</td>
                  <td className="muted">{e.transferId ? `#${e.transferId.slice(0, 8)}` : 'opening'}</td>
                  <td>{formatIdr(e.balanceAfter)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div>
          <h2>Notifications <span className="muted small">via Kafka</span></h2>
          <ul className="log">
            {notes.map((n) => <li key={n.id}>{n.message}</li>)}
            {notes.length === 0 && <li className="muted">Nothing yet. Events land a moment after the transfer.</li>}
          </ul>
        </div>
      </div>
    </section>
  )
}
