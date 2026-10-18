package dev.pi.mobile.backend

/**
 * A bridge failure that carries a Pi Durable `FileError`/`ExecutionError` code.
 *
 * The JS side (`runtime/src/env.mjs`) maps `code` back onto the portable error types the
 * harness expects (`not_found`, `is_directory`, `invalid`, `aborted`, ...), so the Kotlin
 * adapter must name failures rather than let them degrade to `unknown`.
 */
class NativeFailure(val code: String, message: String) : Exception(message)
