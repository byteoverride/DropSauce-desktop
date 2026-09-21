package org.koitharu.kotatsu.desktop.feature.curate

/**
 * Which library entries the user has ticked.
 *
 * Immutable and free of Compose, Room and coroutines, so the rules that are easy to get
 * wrong (invert, select-all under a filter, what happens when the list changes beneath
 * the user) are decided here and tested without a screen.
 *
 * Holds ids rather than items on purpose. The list under a selection is a `Flow` that
 * re-emits whenever anything in the library changes, so holding items would mean the
 * selection quietly pointed at stale copies of rows that have since been edited.
 */
data class Selection(val ids: Set<Long> = emptySet()) {

	val size: Int get() = ids.size

	val isEmpty: Boolean get() = ids.isEmpty()

	val isNotEmpty: Boolean get() = ids.isNotEmpty()

	operator fun contains(id: Long): Boolean = id in ids

	fun select(id: Long): Selection = if (id in ids) this else Selection(ids + id)

	fun deselect(id: Long): Selection = if (id in ids) Selection(ids - id) else this

	fun toggle(id: Long): Selection = if (id in ids) deselect(id) else select(id)

	/**
	 * Adds everything currently on screen.
	 *
	 * [available] is what the user can see, which is already filtered and sorted. Anything
	 * selected but filtered out stays selected: hiding a row is not the same as unticking
	 * it, and silently dropping it would delete part of a selection the user built up
	 * while switching categories.
	 */
	fun selectAll(available: Collection<Long>): Selection =
		if (available.isEmpty()) this else Selection(ids + available)

	/**
	 * Flips every visible row.
	 *
	 * Only [available] is flipped. Ids selected while a different filter was active are
	 * left alone for the same reason [selectAll] keeps them: invert is a statement about
	 * what is on screen, and the user cannot see the rest to judge it.
	 */
	fun invert(available: Collection<Long>): Selection {
		if (available.isEmpty()) return this
		val visible = available.toSet()
		val offScreen = ids - visible
		val flipped = visible - ids
		return Selection(offScreen + flipped)
	}

	fun clear(): Selection = if (ids.isEmpty()) this else Selection(emptySet())

	/**
	 * Drops ids that no longer exist.
	 *
	 * Call this with everything the library holds, not with the visible page: a batch
	 * action run against an id that was deleted underneath the user would either fail or,
	 * worse, hit a recycled row. Filtering is [selectAll]'s problem, deletion is this one's.
	 */
	fun retaining(existing: Collection<Long>): Selection {
		if (ids.isEmpty()) return this
		val kept = ids.intersect(existing.toSet())
		return if (kept.size == ids.size) this else Selection(kept)
	}

	/** The selection in the order [items] are shown, so batch actions are deterministic. */
	fun orderedBy(items: List<Long>): List<Long> = items.filter { it in ids }

	companion object {

		val EMPTY = Selection(emptySet())
	}
}
