package com.bitchat.android.services.meshgraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies the shortest-path planner used to build source routes for multi-hop
 * A→B→C delivery. Routes only travel over two-way (confirmed) gossip edges.
 */
class RoutePlannerTest {

    @Before
    fun setUp() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `three node line yields A B C path over confirmed edges`() {
        val graph = MeshGraphService.getInstance()
        // A <-> B direct; B <-> C direct. Announcements from both sides confirm each edge.
        graph.updateFromAnnouncement("A", "Alice", listOf("B"), 1UL)
        graph.updateFromAnnouncement("B", "Bob", listOf("A", "C"), 1UL)
        graph.updateFromAnnouncement("C", "Charlie", listOf("B"), 1UL)

        val path = RoutePlanner.shortestPath("A", "C")

        assertNotNull("A→C should be routable through B", path)
        assertEquals(listOf("A", "B", "C"), path)
    }

    @Test
    fun `two node direct edge yields direct path`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement("A", "Alice", listOf("B"), 1UL)
        graph.updateFromAnnouncement("B", "Bob", listOf("A"), 1UL)

        val path = RoutePlanner.shortestPath("A", "B")

        assertNotNull(path)
        assertEquals(listOf("A", "B"), path)
    }

    @Test
    fun `unconfirmed one-way edge is not routable`() {
        val graph = MeshGraphService.getInstance()
        // Only A announces B; B never announces A, so the edge stays unconfirmed.
        graph.updateFromAnnouncement("A", "Alice", listOf("B"), 1UL)

        val path = RoutePlanner.shortestPath("A", "B")

        assertNull("One-way edges must be excluded from routing", path)
    }

    @Test
    fun `disconnected nodes yield null path`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement("A", "Alice", listOf("B"), 1UL)
        graph.updateFromAnnouncement("B", "Bob", listOf("A"), 1UL)
        graph.updateFromAnnouncement("D", "Dave", listOf("E"), 1UL)
        graph.updateFromAnnouncement("E", "Erin", listOf("D"), 1UL)

        val path = RoutePlanner.shortestPath("A", "E")

        assertNull("No path should exist between separate components", path)
    }

    @Test
    fun `route through multiple intermediate hops`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement("A", "Alice", listOf("B"), 1UL)
        graph.updateFromAnnouncement("B", "Bob", listOf("A", "D"), 1UL)
        graph.updateFromAnnouncement("D", "Dan", listOf("B", "C"), 1UL)
        graph.updateFromAnnouncement("C", "Charlie", listOf("D"), 1UL)

        val path = RoutePlanner.shortestPath("A", "C")

        assertNotNull(path)
        assertEquals(listOf("A", "B", "D", "C"), path)
    }
}
