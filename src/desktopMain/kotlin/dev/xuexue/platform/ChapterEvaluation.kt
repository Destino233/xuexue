package dev.xuexue.platform

data class ChapterEvaluationMetrics(
    val titlePrecision: Float,
    val titleRecall: Float,
    val hierarchyEdgeAccuracy: Float,
    val targetPageAccuracy: Float,
    val expectedTitles: Int,
    val actualTitles: Int,
    val matchedTitles: Int,
)

object ChapterEvaluator {
    fun evaluate(
        expected: PdfOutlineDocument,
        actual: PdfOutlineDocument,
    ): ChapterEvaluationMetrics {
        val expectedNodes = flatten(expected.nodes)
        val actualNodes = flatten(actual.nodes)
        val unusedExpected = expectedNodes.indices.toMutableSet()
        val matches = mutableListOf<Pair<EvaluationNode, EvaluationNode>>()
        actualNodes.forEach { candidate ->
            val matchIndex =
                unusedExpected.firstOrNull { index ->
                    normalizeTitle(expectedNodes[index].title) == normalizeTitle(candidate.title)
                }
            if (matchIndex != null) {
                unusedExpected.remove(matchIndex)
                matches += expectedNodes[matchIndex] to candidate
            }
        }
        val matched = matches.size
        val expectedEdges =
            expectedNodes
                .mapNotNull { node ->
                    node.parent?.let { parent -> normalizeTitle(parent) to normalizeTitle(node.title) }
                }.toSet()
        val actualEdges =
            actualNodes
                .mapNotNull { node ->
                    node.parent?.let { parent -> normalizeTitle(parent) to normalizeTitle(node.title) }
                }.toSet()
        val matchedPages =
            matches.count { (expectedNode, actualNode) ->
                expectedNode.pageIndex == actualNode.pageIndex
            }
        return ChapterEvaluationMetrics(
            titlePrecision = ratio(matched, actualNodes.size),
            titleRecall = ratio(matched, expectedNodes.size),
            hierarchyEdgeAccuracy =
                ratio(
                    expectedEdges.intersect(actualEdges).size,
                    expectedEdges.union(actualEdges).size,
                ),
            targetPageAccuracy = ratio(matchedPages, matched),
            expectedTitles = expectedNodes.size,
            actualTitles = actualNodes.size,
            matchedTitles = matched,
        )
    }

    private fun flatten(
        nodes: List<PdfOutlineNode>,
        parent: String? = null,
    ): List<EvaluationNode> =
        nodes.flatMap { node ->
            val current = EvaluationNode(node.title, node.destination.pageIndex, parent)
            listOf(current) + flatten(node.children, node.title)
        }

    private fun normalizeTitle(title: String): String = title.replace(Regex("\\s+"), " ").trim().lowercase()

    private fun ratio(
        numerator: Int,
        denominator: Int,
    ): Float = if (denominator == 0) 1f else numerator.toFloat() / denominator

    private data class EvaluationNode(
        val title: String,
        val pageIndex: Int,
        val parent: String?,
    )
}
