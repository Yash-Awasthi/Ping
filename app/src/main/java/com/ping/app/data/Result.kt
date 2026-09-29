package com.ping.app.data

/**
 * Result wrapper for type-safe error handling.
 * Follows android-kotlin skill pattern for sealed class state management.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val exception: Throwable) : Result<Nothing>
    data object Loading : Result<Nothing>
}

fun <T> Result<T>.getOrNull(): T? = (this as? Result.Success)?.data

fun <T> Result<T>.getOrDefault(default: @UnsafeVariance T): T =
    when (this) {
        is Result.Success -> data
        else -> default
    }

inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.Success(transform(data))
    is Result.Error -> this
    is Result.Loading -> this
}

inline fun <T, R> Result<T>.mapCatching(transform: (T) -> R): Result<R> = try {
    when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
        is Result.Loading -> this
    }
} catch (e: Throwable) {
    Result.Error(e)
}

suspend inline fun <T> runCatchingResult(crossinline block: suspend () -> T): Result<T> = try {
    Result.Success(block())
} catch (e: Throwable) {
    Result.Error(e)
}
