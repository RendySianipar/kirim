package dev.rendy.kirim.account

import dev.rendy.kirim.security.AccountAccess
import dev.rendy.kirim.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class OpenAccountRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val ownerName: String,

    @field:Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code such as IDR")
    val currency: String = "IDR",

    @field:PositiveOrZero
    val openingBalance: Long = 0,
)

data class AccountResponse(
    val id: UUID,
    val ownerName: String,
    val currency: String,
    val balance: Long,
    val createdAt: Instant,
    /** Keycloak user id of the owner (null for accounts from before authentication). */
    val ownerId: String?,
) {
    companion object {
        fun from(a: Account) = AccountResponse(a.id, a.ownerName, a.currency, a.balance, a.createdAt, a.ownerId)
    }
}

data class LedgerEntryResponse(
    val id: UUID,
    val transferId: UUID?,
    val type: EntryType,
    val amount: Long,
    val balanceAfter: Long,
    val createdAt: Instant,
) {
    companion object {
        fun from(e: LedgerEntry) = LedgerEntryResponse(e.id, e.transferId, e.type, e.amount, e.balanceAfter, e.createdAt)
    }
}

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val service: AccountService,
    private val access: AccountAccess,
    private val currentUser: CurrentUser,
) {

    /** The new account belongs to the caller: the owner comes from the token, never from the request body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@Valid @RequestBody request: OpenAccountRequest): AccountResponse =
        AccountResponse.from(service.open(request.ownerName, request.currency, request.openingBalance, currentUser.get().subject))

    /** Customers see their own accounts; OPS sees all. */
    @GetMapping
    fun list(): List<AccountResponse> {
        val caller = currentUser.get()
        val accounts = if (caller.isOps) service.list() else service.listByOwner(caller.subject)
        return accounts.map { AccountResponse.from(it) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AccountResponse = AccountResponse.from(access.requireReadable(id))

    @GetMapping("/{id}/entries")
    fun statement(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): List<LedgerEntryResponse> {
        access.requireReadable(id)
        return service.statement(id, limit).map { LedgerEntryResponse.from(it) }
    }
}
