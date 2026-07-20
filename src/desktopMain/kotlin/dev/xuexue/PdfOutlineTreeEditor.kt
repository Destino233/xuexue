package dev.xuexue

import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource

// Pure tree operations stay together so every edit preserves outline hierarchy invariants.
@Suppress("TooManyFunctions")
internal object PdfOutlineTreeEditor {
    fun updateNode(
        nodes: List<PdfOutlineNode>,
        id: String,
        transform: (PdfOutlineNode) -> PdfOutlineNode,
    ): List<PdfOutlineNode> =
        nodes.map { node ->
            if (node.id == id) {
                transform(node).copy(
                    children =
                        node.children.map { child ->
                            updateNode(listOf(child), id, transform).first()
                        },
                )
            } else {
                node.copy(children = updateNode(node.children, id, transform))
            }
        }

    fun removeNode(
        nodes: List<PdfOutlineNode>,
        id: String,
    ): Pair<List<PdfOutlineNode>, PdfOutlineNode?> {
        val direct = nodes.indexOfFirst { node -> node.id == id }
        if (direct >= 0) return nodes.filterIndexed { index, _ -> index != direct } to nodes[direct]
        var removed: PdfOutlineNode? = null
        val updated =
            nodes.map { node ->
                if (removed != null) {
                    node
                } else {
                    val (children, child) = removeNode(node.children, id)
                    removed = child
                    node.copy(children = children)
                }
            }
        return updated to removed
    }

    fun moveNode(
        nodes: List<PdfOutlineNode>,
        id: String,
        delta: Int,
    ): List<PdfOutlineNode> =
        findPath(nodes, id)?.let { path ->
            val parentPath = path.dropLast(1)
            val index = path.last()
            nodesAtPath(nodes, parentPath)?.let { siblings ->
                val target = (index + delta).coerceIn(0, siblings.lastIndex)
                if (target == index) {
                    nodes
                } else {
                    val reordered = siblings.toMutableList()
                    val moved = reordered.removeAt(index).markManual()
                    reordered.add(target, moved)
                    replaceAtPath(nodes, parentPath, reordered)
                }
            }
        } ?: nodes

    fun changeLevel(
        nodes: List<PdfOutlineNode>,
        id: String,
        indent: Boolean,
    ): List<PdfOutlineNode> = if (indent) indentNode(nodes, id) else outdentNode(nodes, id)

    fun confirmHighConfidence(
        nodes: List<PdfOutlineNode>,
        threshold: Float,
    ): List<PdfOutlineNode> =
        nodes.map { node ->
            val updated =
                if (node.reviewState == PdfOutlineReviewState.CANDIDATE &&
                    (node.confidence ?: 0f) >= threshold &&
                    (node.evidence?.signals?.getOrDefault("printedPageMapping", 1f) ?: 1f) >=
                    0.9f
                ) {
                    node.copy(reviewState = PdfOutlineReviewState.CONFIRMED)
                } else {
                    node
                }
            updated.copy(children = confirmHighConfidence(updated.children, threshold))
        }

    fun exportable(
        nodes: List<PdfOutlineNode>,
        confirmedOnly: Boolean,
    ): List<PdfOutlineNode> =
        nodes.flatMap { node ->
            val children = exportable(node.children, confirmedOnly)
            val confirmed =
                node.reviewState == PdfOutlineReviewState.CONFIRMED ||
                    node.reviewState == PdfOutlineReviewState.MANUAL
            val included = node.reviewState != PdfOutlineReviewState.REJECTED && (!confirmedOnly || confirmed)
            if (included) {
                listOf(node.copy(children = children))
            } else {
                children
            }
        }

    fun confirmedOnly(nodes: List<PdfOutlineNode>): List<PdfOutlineNode> = exportable(nodes, confirmedOnly = true)

    private fun indentNode(
        nodes: List<PdfOutlineNode>,
        id: String,
    ): List<PdfOutlineNode> =
        findPath(nodes, id)
            ?.takeIf { path -> path.last() > 0 }
            ?.let { path ->
                val parentPath = path.dropLast(1)
                nodesAtPath(nodes, parentPath)?.let { siblings ->
                    val moved = siblings[path.last()].markManual()
                    val reduced = siblings.toMutableList().apply { removeAt(path.last()) }
                    val previous = reduced[path.last() - 1]
                    reduced[path.last() - 1] =
                        previous.copy(
                            children = previous.children + moved,
                            reviewState = PdfOutlineReviewState.MANUAL,
                        )
                    replaceAtPath(nodes, parentPath, reduced)
                }
            } ?: nodes

    private fun outdentNode(
        nodes: List<PdfOutlineNode>,
        id: String,
    ): List<PdfOutlineNode> =
        findPath(nodes, id)
            ?.takeIf { path -> path.size >= 2 }
            ?.let { path ->
                val parentPath = path.dropLast(1)
                val parentIndex = parentPath.last()
                val grandparentPath = parentPath.dropLast(1)
                nodesAtPath(nodes, parentPath)?.let { siblings ->
                    val moved = siblings[path.last()].markManual()
                    val reducedParent = siblings.filterIndexed { index, _ -> index != path.last() }
                    val updated = replaceAtPath(nodes, parentPath, reducedParent)
                    nodesAtPath(updated, grandparentPath)?.let { grandparent ->
                        val insertionIndex = (parentIndex + 1).coerceAtMost(grandparent.size)
                        val updatedGrandparent =
                            grandparent.toMutableList().apply {
                                add(insertionIndex, moved)
                            }
                        replaceAtPath(updated, grandparentPath, updatedGrandparent)
                    }
                }
            } ?: nodes

    private fun findPath(
        nodes: List<PdfOutlineNode>,
        id: String,
        prefix: List<Int> = emptyList(),
    ): List<Int>? {
        val direct = nodes.indexOfFirst { node -> node.id == id }
        return if (direct >= 0) {
            prefix + direct
        } else {
            nodes
                .asSequence()
                .mapIndexedNotNull { index, node ->
                    findPath(node.children, id, prefix + index)
                }.firstOrNull()
        }
    }

    private fun nodesAtPath(
        nodes: List<PdfOutlineNode>,
        path: List<Int>,
    ): List<PdfOutlineNode>? =
        if (path.isEmpty()) {
            nodes
        } else {
            nodes.getOrNull(path.first())?.let { node ->
                nodesAtPath(node.children, path.drop(1))
            }
        }

    private fun replaceAtPath(
        nodes: List<PdfOutlineNode>,
        path: List<Int>,
        replacement: List<PdfOutlineNode>,
    ): List<PdfOutlineNode> =
        if (path.isEmpty()) {
            replacement
        } else {
            val index = path.first()
            nodes.mapIndexed { currentIndex, node ->
                if (currentIndex == index) {
                    node.copy(children = replaceAtPath(node.children, path.drop(1), replacement))
                } else {
                    node
                }
            }
        }

    private fun PdfOutlineNode.markManual(): PdfOutlineNode =
        copy(
            source = PdfOutlineSource.MANUAL,
            reviewState = PdfOutlineReviewState.MANUAL,
            confidence = 1f,
        )
}
