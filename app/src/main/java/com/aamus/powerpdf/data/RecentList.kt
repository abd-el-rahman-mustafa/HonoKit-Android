package com.aamus.powerpdf.data

/**
 * One rule for "the same file" in Recent files, shared by Home's list, the chooser sheet and the
 * store (decisions §48). Pure, unit-tested in RecentListTest.
 *
 * A file can reach Recent by two roads with two addresses: "Open with" from another app (a content
 * Uri, read through a private copy) and from inside HonoKit (the picker's document Uri, a file path).
 * The phone test of 2026-10-04 showed pdf-sample.pdf twice in the chooser that way. Two entries are
 * the same file when they are the same kind and either have the same address, or the same name
 * (ignoring case) and the same size in bytes. The newest is kept; a star on any of them stays.
 */
object RecentList {
    fun sameFile(a: RecentFile, b: RecentFile): Boolean {
        if (a.kind != b.kind) return false
        if (a.uri == b.uri) return true
        return a.size > 0 && a.size == b.size && a.name.equals(b.name, ignoreCase = true)
    }

    /**
     * [all] with each file once: the newest entry of each file, in [all]'s order, starred when any
     * entry for that file was starred.
     */
    fun distinct(all: List<RecentFile>): List<RecentFile> {
        val out = ArrayList<RecentFile>()
        val taken = BooleanArray(all.size)
        for (i in all.indices) {
            if (taken[i]) continue
            val group = ArrayList<RecentFile>()
            for (j in i until all.size) if (!taken[j] && (j == i || sameFile(all[i], all[j]))) {
                taken[j] = true
                group.add(all[j])
            }
            val newest = group.maxBy { it.openedAt }
            out.add(if (group.any { it.starred } && !newest.starred) newest.copy(starred = true) else newest)
        }
        // In the order the list had: the position of each file's first entry (newest-first lists keep their order).
        return out
    }

    /** The other entries in [all] that are the same file as [entry]. */
    fun others(entry: RecentFile, all: List<RecentFile>): List<RecentFile> = all.filter { it.id != entry.id && sameFile(entry, it) }
}
