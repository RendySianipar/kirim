package dev.rendy.kirim.security

import dev.rendy.kirim.account.Account
import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.common.AccountNotFoundException
import dev.rendy.kirim.common.AccountNotOwnedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import java.util.UUID

/** Who is calling: the token's subject (Keycloak user id) and whether they are OPS. */
data class Caller(val subject: String, val username: String, val isOps: Boolean)

@Component
class CurrentUser {
    fun get(): Caller {
        val auth = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
            ?: throw IllegalStateException("No authenticated user; is this endpoint behind the security filter?")
        return Caller(
            subject = auth.token.subject,
            username = auth.token.getClaimAsString("preferred_username") ?: auth.name,
            isOps = auth.authorities.any { it.authority == "ROLE_${Roles.OPS}" },
        )
    }
}

/**
 * Data-dependent authorization: "is this account yours?".
 *
 * - Reading an account (balance, statement, notifications): its owner, or OPS.
 *   Anyone else gets 404, not 403, so account ids cannot be probed.
 * - Sending money from an account: its owner only. OPS can look but not move
 *   customer money (separation of duties).
 * - Accounts without an owner (opened before authentication existed) can only
 *   be read by OPS and cannot be sent from through the API.
 */
@Component
class AccountAccess(private val accounts: AccountService, private val currentUser: CurrentUser) {

    fun requireReadable(accountId: UUID): Account {
        val account = accounts.get(accountId)
        if (!canRead(account, currentUser.get())) throw AccountNotFoundException(accountId)
        return account
    }

    fun requireOwned(accountId: UUID): Account {
        val account = accounts.get(accountId)
        val caller = currentUser.get()
        if (account.ownerId == null || account.ownerId != caller.subject) {
            if (!canRead(account, caller)) throw AccountNotFoundException(accountId)
            throw AccountNotOwnedException(accountId)
        }
        return account
    }

    /** True if the caller may see this account. */
    fun canRead(account: Account, caller: Caller = currentUser.get()): Boolean =
        caller.isOps || (account.ownerId != null && account.ownerId == caller.subject)
}
