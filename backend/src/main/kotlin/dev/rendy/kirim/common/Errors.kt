package dev.rendy.kirim.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.util.UUID

/** Base class for errors that map cleanly onto an HTTP status and a stable error code. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
) : RuntimeException(message)

class AccountNotFoundException(id: UUID) :
    ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account $id was not found")

class TransferNotFoundException(id: UUID) :
    ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer $id was not found")

class InvalidRequestException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)

class IdempotencyKeyReusedException(key: String) :
    ApiException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "IDEMPOTENCY_KEY_REUSED",
        "Idempotency-Key '$key' was already used with a different request body",
    )

class IdempotentRequestInProgressException(key: String) :
    ApiException(
        HttpStatus.CONFLICT,
        "REQUEST_IN_PROGRESS",
        "A request with Idempotency-Key '$key' is still being processed; retry shortly",
    )

/**
 * Every error leaves the API as RFC 9457 Problem Details (application/problem+json).
 * Extending ResponseEntityExceptionHandler gives the same shape to Spring's own
 * errors (validation failures, missing headers, unreadable JSON).
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: ex.code).apply {
            title = ex.status.reasonPhrase
            setProperty("code", ex.code)
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid request").apply {
            title = HttpStatus.BAD_REQUEST.reasonPhrase
            setProperty("code", "INVALID_REQUEST")
        }
}
