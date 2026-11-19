package zoukan.desktop.ui.common

/**
 * Immutable multi-selection state over item identifiers of type [ID], supporting single-item
 * selection/toggling, Shift-click range selection, select-all, invert, retaining valid IDs on
 * list updates, and clearing selection mode.
 */
data class ListSelection<ID>(
    val ids: Set<ID> = emptySet(),
    val active: Boolean = false,
) {
    val isEmpty: Boolean get() = ids.isEmpty()
    val isNotEmpty: Boolean get() = ids.isNotEmpty()
    val selecting: Boolean get() = active || ids.isNotEmpty()
    val size: Int get() = ids.size

    operator fun contains(id: ID): Boolean = id in ids

    fun start(): ListSelection<ID> = if (active) this else copy(active = true)

    fun select(id: ID): ListSelection<ID> = if (id in ids) this else copy(ids = ids + id)

    fun select(targets: Collection<ID>): ListSelection<ID> =
        if (targets.isEmpty()) this else copy(ids = ids + targets)

    fun deselect(id: ID): ListSelection<ID> = if (id !in ids) this else copy(ids = ids - id)

    fun deselect(targets: Collection<ID>): ListSelection<ID> =
        if (targets.isEmpty()) this else copy(ids = ids - targets.toSet())

    fun toggle(id: ID): ListSelection<ID> =
        copy(ids = if (id in ids) ids - id else ids + id)

    /**
     * Toggles a whole [group] of IDs using [clicked] as the anchor: if [clicked] is not currently
     * selected, selects every ID in [group]; otherwise deselects every ID in [group].
     */
    fun toggleGroup(group: Collection<ID>, clicked: ID): ListSelection<ID> {
        if (group.isEmpty()) return this
        return if (clicked !in ids) select(group) else deselect(group)
    }

    /**
     * Selects every ID in [orderedIds] between the last currently selected item and [target].
     * Returns this selection unchanged if [target] is not present in [orderedIds]; if nothing is
     * selected yet, selects [target] alone.
     */
    fun selectRange(orderedIds: List<ID>, target: ID): ListSelection<ID> {
        val to = orderedIds.indexOf(target)
        if (to < 0) return this
        val from = orderedIds.indexOfLast { it in ids }.takeIf { it >= 0 } ?: to
        val range = if (from <= to) from..to else to..from
        return copy(ids = ids + range.map { orderedIds[it] })
    }

    fun selectAll(allIds: Iterable<ID>): ListSelection<ID> =
        copy(ids = allIds.toCollection(LinkedHashSet()))

    fun invert(orderedIds: Iterable<ID>): ListSelection<ID> =
        copy(ids = orderedIds.filterTo(LinkedHashSet()) { it !in ids })

    /** Drops any selected IDs that are no longer in [validIds]. */
    fun retain(validIds: Set<ID>): ListSelection<ID> {
        val filtered = ids.filterTo(LinkedHashSet()) { it in validIds }
        return if (filtered.size == ids.size) this else copy(ids = filtered)
    }

    fun clear(): ListSelection<ID> = if (ids.isEmpty() && !active) this else ListSelection()
}
