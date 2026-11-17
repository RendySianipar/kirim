package dev.rendy.kirim.account

import dev.rendy.kirim.common.AccountNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class AccountService(
    private val accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val clock: Clock,
) {

    @Transactional
    fun open(ownerName: String, currency: String, openingBalance: Long): Account {
        require(ownerName.isNotBlank()) { "ownerName must not be blank" }
        require(openingBalance >= 0) { "openingBalance must not be negative" }

        val now = clock.instant()
        val account = Account(UUID.randomUUID(), ownerName.trim(), currency, openingBalance, now)
        accounts.insert(account)

        if (openingBalance > 0) {
            ledger.insert(
                LedgerEntry(UUID.randomUUID(), account.id, null, EntryType.CREDIT, openingBalance, openingBalance, now),
            )
        }
        return account
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Account = accounts.findById(id) ?: throw AccountNotFoundException(id)

    @Transactional(readOnly = true)
    fun list(): List<Account> = accounts.findAll()

    @Transactional(readOnly = true)
    fun statement(id: UUID, limit: Int): List<LedgerEntry> {
        get(id) // 404 if the account doesn't exist
        return ledger.findByAccount(id, limit.coerceIn(1, 100))
    }
}
