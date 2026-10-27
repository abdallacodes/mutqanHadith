package com.mutqan.hadith.domain.catalog

/**
 * Brings the library up to the cloud catalog. The cloud keeps one revision that changes whenever an admin saves, so the
 * usual answer costs one small read: nothing changed. Only when the revision moved are the changed documents pulled, from
 * the cursor on, and applied in one transaction.
 *
 * A document that is not valid (a chain naming a narrator that does not exist, say) is left out and counted. The cursor
 * then stays at that document's time, so a later pull tries it again after the app or the document is fixed; the revision
 * is still recorded, so the pull is not repeated at every start.
 */
class SyncCatalogUseCase(
    private val remote: RemoteCatalog,
    private val store: CatalogStore,
    private val states: CatalogSyncStateStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(): CatalogOutcome {
        val before = states.read()
        val revision = remote.revision()
        if (revision == null) {
            states.write(before.copy(checkedAt = now()))
            return CatalogOutcome.NoCloudCatalog
        }
        if (revision == before.revision) {
            states.write(before.copy(checkedAt = now()))
            return CatalogOutcome.UpToDate
        }

        val delta = remote.changesSince(before.cursor)
        val result = if (delta.isEmpty) ApplyResult(0, 0) else store.apply(delta)
        val cursor = result.earliestSkipped ?: maxOf(before.cursor, delta.latest)
        states.write(
            before.copy(
                revision = revision,
                cursor = cursor,
                checkedAt = now(),
                updatedAt = if (result.applied > 0) now() else before.updatedAt,
            ),
        )
        return CatalogOutcome.Updated(result.applied, result.skipped + delta.unreadable)
    }
}
