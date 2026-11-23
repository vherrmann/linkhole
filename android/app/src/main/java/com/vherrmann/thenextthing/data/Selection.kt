package com.vherrmann.thenextthing.data

import kotlin.random.Random

/**
 * Mirrors the server: "latest" is the newest unread item and is derived, never
 * stored. Lists are expected newest first.
 */
object Selection {
    fun latest(unreadNewestFirst: List<Item>): Item? = unreadNewestFirst.firstOrNull()

    /** A random unread item other than the latest; the latest if it is the only one. */
    fun random(unreadNewestFirst: List<Item>, rnd: Random = Random): Item? = when (unreadNewestFirst.size) {
        0 -> null
        1 -> unreadNewestFirst[0]
        else -> unreadNewestFirst[1 + rnd.nextInt(unreadNewestFirst.size - 1)]
    }
}
