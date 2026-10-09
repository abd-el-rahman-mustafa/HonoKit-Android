package com.aamus.powerpdf.engine

import java.util.IdentityHashMap

/**
 * Comment threads for the comments list (desktop src/shared/threads.ts, decisions §82
 * there, §52 here): a reply (/IRT) and a review state go under the comment they answer, wherever in
 * the document they sit. A reply whose comment is not in the list (or a loop of replies) is shown
 * as a comment of its own.
 *
 * [id] gives each item's object number (0 = none), [replyTo] the number it answers, [state] its
 * review state (Accepted, Rejected…) or null.
 */
class CommentThreads<T : Any>(items: List<T>, id: (T) -> Int, private val replyTo: (T) -> Int?, private val state: (T) -> String?) {
    /** The comments shown on their own, in the list's order. */
    val top: List<T>

    /** Each item's replies and states, in the list's order (by identity: equal items stay apart). */
    private val children = IdentityHashMap<T, MutableList<T>>()

    init {
        val byId = HashMap<Int, T>()
        for (m in items) {
            val n = id(m)
            if (n > 0 && n !in byId) byId[n] = m
        }
        // Where an item's chain of replies leads: null when it is a comment of its own.
        fun parentOf(m: T): T? {
            val parent = replyTo(m)?.let { byId[it] } ?: return null
            if (parent === m) return null
            // A loop of replies (A answers B, B answers A) has no comment at its head: shown on its own.
            val seen = IdentityHashMap<T, Boolean>()
            seen[m] = true
            var at: T? = parent
            while (at != null) {
                if (seen.put(at, true) != null || seen.size > 256) return null
                at = replyTo(at)?.let { byId[it] }
            }
            return parent
        }
        val tops = ArrayList<T>()
        for (m in items) {
            val parent = parentOf(m)
            if (parent == null) tops.add(m) else children.getOrPut(parent) { ArrayList() }.add(m)
        }
        top = tops
    }

    /** A comment's replies and their replies, depth first, in the list's order; depth 1 = a direct reply. Review states left out. */
    fun repliesOf(item: T): List<Pair<T, Int>> {
        val out = ArrayList<Pair<T, Int>>()
        fun walk(m: T, depth: Int) {
            for (c in children[m].orEmpty()) {
                if (state(c) != null) continue
                out.add(c to depth)
                walk(c, depth + 1)
            }
        }
        walk(item, 1)
        return out
    }

    /** The review states set directly on a comment. */
    fun statesOf(item: T): List<T> = children[item].orEmpty().filter { state(it) != null }
}
