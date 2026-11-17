package dev.rendy.kirim.common

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

// Small helpers so repositories read cleanly. The PostgreSQL driver does not
// accept java.time.Instant directly, so we convert at the edge.

fun Instant.toTimestamp(): Timestamp = Timestamp.from(this)

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
