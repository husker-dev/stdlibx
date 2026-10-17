package com.huskerdev.stdlibx.io

/** A single-pass stream of values with a synchronous or suspending interface. */
interface Stream<out T>

/**
 * A closeable suspending iterator. After close,
 * `hasNext()` returns false and `next()` throws [IllegalStateException].
 */
interface AsyncStream<out T>: Stream<T>, AsyncCloseable {
    /** Returns the next value, throwing [NoSuchElementException] when exhausted. */
    suspend operator fun next(): T

    /** Returns whether another value is available. */
    suspend operator fun hasNext(): Boolean

    /** A stream backed by an existing [list] of values. */
    class OfList<T>(
        val list: List<T>
    ): AsyncStream<T> {
        val iterator = list.iterator()
        private var closed = false

        override suspend fun close() {
            closed = true
        }

        override suspend fun hasNext(): Boolean =
            !closed && iterator.hasNext()

        override suspend fun next(): T {
            check(!closed) { "Stream is closed" }
            return iterator.next()
        }
    }
}

/**
 * A closeable blocking iterator. After close,
 * `hasNext()` returns false and `next()` throws [IllegalStateException].
 */
interface SyncStream<out T>: Stream<T>, AutoCloseable, Iterator<T> {

    /** A stream backed by an existing [list] of values. */
    class OfList<T>(
        val list: List<T>
    ): SyncStream<T> {
        val iterator = list.iterator()
        private var closed = false

        override fun close() {
            closed = true
        }

        override fun hasNext(): Boolean =
            !closed && iterator.hasNext()

        override fun next(): T {
            check(!closed) { "Stream is closed" }
            return iterator.next()
        }
    }
}
