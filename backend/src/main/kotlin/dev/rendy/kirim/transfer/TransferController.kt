package dev.rendy.kirim.transfer

import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.common.InvalidRequestException
import dev.rendy.kirim.common.TransferNotFoundException
import dev.rendy.kirim.security.AccountAccess
import dev.rendy.kirim.security.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class TransferRequest(
    val fromAccountId: UUID,
    val toAccountId: UUID,

    @field:Positive
    val amount: Long,

    @field:Pattern(regexp = "[A-Z]{3}", message = "must be an ISO-4217 code such as IDR")
    val currency: String = "IDR",

    @field:Size(max = 140)
    val description: String? = null,
) {
    fun toCommand() = TransferCommand(fromAccountId, toAccountId, amount, currency, description)
}

data class TransferResponse(
    val id: UUID,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
    val currency: String,
    val description: String?,
    val status: TransferStatus,
    val failureReason: RejectionReason?,
    val createdAt: Instant,
) {
    companion object {
        fun from(t: Transfer) = TransferResponse(
            t.id, t.fromAccountId, t.toAccountId, t.amount, t.currency, t.description, t.status, t.failureReason, t.createdAt,
        )
    }
}

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val service: TransferService,
    private val accounts: AccountService,
    private val access: AccountAccess,
    private val currentUser: CurrentUser,
) {

    /**
     * Clients send a unique Idempotency-Key per logical transfer (e.g. a UUID
     * generated when the user taps "Send") and reuse it on every retry.
     *
     * - first request: 201 Created
     * - retry with same key + same body: 200 OK, the original transfer, header Idempotent-Replayed: true
     * - same key + different body: 422
     *
     * A business rejection (e.g. insufficient funds) is still a created transfer
     * with status REJECTED, so retrying it returns the same rejection.
     */
    @PostMapping
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: TransferRequest,
    ): ResponseEntity<TransferResponse> {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 100) {
            throw InvalidRequestException("Idempotency-Key must be 1-100 characters")
        }
        // Ownership is checked before the idempotency key is looked at, so another
        // user's key can never be used to read or replay someone else's transfer.
        access.requireOwned(request.fromAccountId)
        val result = service.transfer(idempotencyKey, request.toCommand())
        val body = TransferResponse.from(result.transfer)
        return if (result.replayed) {
            ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body)
        } else {
            ResponseEntity.status(HttpStatus.CREATED).body(body)
        }
    }

    /** Visible to the sender, the receiver and OPS; anyone else gets 404. */
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): TransferResponse {
        val transfer = service.get(id)
        val caller = currentUser.get()
        val visible = caller.isOps ||
            access.canRead(accounts.get(transfer.fromAccountId), caller) ||
            access.canRead(accounts.get(transfer.toAccountId), caller)
        if (!visible) throw TransferNotFoundException(id)
        return TransferResponse.from(transfer)
    }

    companion object {
        const val REPLAYED_HEADER = "Idempotent-Replayed"
    }
}
