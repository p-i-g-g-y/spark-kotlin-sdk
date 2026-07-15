package gy.pig.spark

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import spark.Spark

/**
 * Pure-logic tests for the recovery snapshot classifier. Ported from the Swift
 * SDK's `RecoveryServiceTests.swift` so both SDKs enforce identical bundles.
 */
class RecoveryServiceTests {

    private fun makeNode(
        id: String,
        parent: String? = null,
        owner: ByteArray,
        status: String,
        value: Long = 1000,
        refundTx: ByteArray = byteArrayOf(0xbe.toByte(), 0xef.toByte()),
    ): Spark.TreeNode {
        val builder = Spark.TreeNode.newBuilder()
            .setId(id)
            .setTreeId("tree-1")
            .setValue(value)
            .setNodeTx(ByteString.copyFrom(byteArrayOf(0xde.toByte(), 0xad.toByte())))
            .setRefundTx(ByteString.copyFrom(refundTx))
            .setOwnerIdentityPublicKey(ByteString.copyFrom(owner))
            .setStatus(status)
        if (parent != null) builder.setParentNodeId(parent)
        return builder.build()
    }

    private val me = ByteArray(33) { 0x02 }

    @Test
    fun recoverySnapshotClassifiesLeavesVsAncestorsAndRoundTripsHex() {
        // root -> mid -> leaf chain, plus an unrelated locked leaf
        val root = makeNode(id = "root", owner = me, status = "SPLITTED")
        val mid = makeNode(id = "mid", parent = "root", owner = me, status = "SPLITTED")
        val leaf = makeNode(id = "leaf", parent = "mid", owner = me, status = "AVAILABLE", value = 5000)
        val lockedLeaf = makeNode(id = "locked", parent = "root", owner = me, status = "TRANSFER_LOCKED", value = 250)

        val all = mapOf("root" to root, "mid" to mid, "leaf" to leaf, "locked" to lockedLeaf)
        assertTrue(missingParentIds(all).isEmpty())

        val snapshot = buildRecoverySnapshot(all, identityPublicKey = me, network = "MAINNET")

        assertEquals("MAINNET", snapshot.network)
        assertEquals(listOf("leaf", "locked"), snapshot.leaves.map { it.id })
        assertEquals(listOf("mid", "root"), snapshot.nodes.map { it.id })
        assertEquals(5250L, snapshot.totalLeafSats)
        assertEquals(me.toHexString(), snapshot.identityPublicKeyHex)

        // Hex must decode back to an identical TreeNode carrying the refund tx.
        val leafHex = snapshot.leaves.first { it.id == "leaf" }.treeNodeHex
        val decoded = Spark.TreeNode.parseFrom(leafHex.hexToByteArray())
        assertEquals(leaf, decoded)
        assertTrue(!decoded.refundTx.isEmpty)
        assertArrayEquals(byteArrayOf(0xbe.toByte(), 0xef.toByte()), decoded.refundTx.toByteArray())
    }

    @Test
    fun missingParentsAreDetectedForRepairPass() {
        // Leaf references a parent the bulk query omitted (legacy-root gotcha).
        val leaf = makeNode(id = "leaf", parent = "ghost-root", owner = me, status = "AVAILABLE")
        assertEquals(setOf("ghost-root"), missingParentIds(mapOf("leaf" to leaf)))
    }

    @Test
    fun foreignNodesArePruned() {
        val them = ByteArray(33) { 0x03 }
        val foreign = makeNode(id = "foreign", owner = them, status = "AVAILABLE")

        val snapshot = buildRecoverySnapshot(mapOf("foreign" to foreign), identityPublicKey = me, network = "MAINNET")
        assertTrue(snapshot.leaves.isEmpty())
        assertTrue(snapshot.nodes.isEmpty())
    }

    @Test
    fun historicalNodesOffCurrentChainsArePruned() {
        val root = makeNode(id = "root", owner = me, status = "SPLITTED")
        val leaf = makeNode(id = "leaf", parent = "root", owner = me, status = "AVAILABLE")
        // Old split intermediate under the same root whose sats moved on long ago:
        // it is nobody's parent and not owned-status, so no exit package needs it.
        val stale = makeNode(id = "stale", parent = "root", owner = me, status = "SPLITTED")
        // A whole disconnected historical tree.
        val oldRoot = makeNode(id = "old-root", owner = me, status = "SPLITTED")

        val snapshot = buildRecoverySnapshot(
            mapOf("root" to root, "leaf" to leaf, "stale" to stale, "old-root" to oldRoot),
            identityPublicKey = me,
            network = "MAINNET",
        )
        assertEquals(listOf("leaf"), snapshot.leaves.map { it.id })
        assertEquals(listOf("root"), snapshot.nodes.map { it.id })
    }

    @Test
    fun holeInNeededChainThrows() {
        val leaf = makeNode(id = "leaf", parent = "ghost", owner = me, status = "AVAILABLE")
        try {
            buildRecoverySnapshot(mapOf("leaf" to leaf), identityPublicKey = me, network = "MAINNET")
            throw AssertionError("Expected SparkError for broken ancestor chain")
        } catch (e: SparkError.InvalidResponse) {
            assertTrue(e.message.contains("missing ancestor ghost"))
        }
    }
}
