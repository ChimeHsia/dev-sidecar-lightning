package acr.browser.lightning.browser.tab

import java.lang.ref.WeakReference
import java.security.MessageDigest

/**
 * Shares large immutable histories within one browser session without retaining closed tabs.
 * Each normal/incognito presenter owns its own pool.
 */
class TabSnapshotPool(
    private val minimumBytes: Int = 64 * 1024,
    private val maximumEntries: Int = 64,
) {
    init {
        require(minimumBytes > 0)
        require(maximumEntries > 0)
    }

    private val entries = LinkedHashMap<StateKey, WeakReference<ByteArray>>(16, 0.75f, true)

    @Synchronized
    internal fun intern(encodedState: ByteArray): ByteArray {
        if (encodedState.size < minimumBytes) return encodedState

        entries.entries.removeAll { it.value.get() == null }
        val key = StateKey(
            encodedState.size,
            MessageDigest.getInstance("SHA-256").digest(encodedState),
        )
        val existing = entries[key]?.get()
        // Even a digest collision must never substitute another page's state.
        if (existing != null && existing.contentEquals(encodedState)) return existing

        entries[key] = WeakReference(encodedState)
        while (entries.size > maximumEntries) {
            val iterator = entries.entries.iterator()
            iterator.next()
            iterator.remove()
        }
        return encodedState
    }

    private class StateKey(
        private val size: Int,
        private val digest: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StateKey && size == other.size && digest.contentEquals(other.digest)

        override fun hashCode(): Int = 31 * size + digest.contentHashCode()
    }
}
