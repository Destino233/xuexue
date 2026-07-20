package dev.xuexue

import dev.xuexue.core.DocumentKind
import dev.xuexue.platform.PdfNativeOutlineNode
import dev.xuexue.platform.PdfOutlineDestination
import dev.xuexue.platform.PdfOutlineDocument
import dev.xuexue.platform.PdfOutlineExportStrategy
import dev.xuexue.platform.PdfOutlineNode
import dev.xuexue.platform.PdfOutlineReviewState
import dev.xuexue.platform.PdfOutlineSource
import dev.xuexue.platform.WorkspaceService
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun resolvePdfPath(
    workspaceService: WorkspaceService,
    root: Path,
    document: DocumentTab,
): Path {
    require(document.kind == DocumentKind.PDF) { "The document is not a PDF." }
    val path = workspaceService.resolveExistingInside(root, document.relativePath)
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        "PDF is outside the workspace or no longer exists."
    }
    return path
}

internal fun nativeOutlineDocument(
    relativePath: String,
    fingerprint: String,
    nodes: List<PdfNativeOutlineNode>,
): PdfOutlineDocument {
    var index = 0

    fun convert(node: PdfNativeOutlineNode): PdfOutlineNode =
        PdfOutlineNode(
            id = "native-${index++}",
            title = node.title,
            destination = PdfOutlineDestination(node.pageIndex, node.topNormalized),
            source = PdfOutlineSource.NATIVE,
            confidence = 1f,
            reviewState = PdfOutlineReviewState.CONFIRMED,
            children = node.children.filter { child -> child.pageIndex >= 0 }.map(::convert),
        )

    return PdfOutlineDocument(
        relativePath = relativePath,
        documentFingerprint = fingerprint,
        nodes = nodes.filter { node -> node.pageIndex >= 0 }.map(::convert),
    )
}

internal data class PdfOutlineReviewSummary(
    val total: Int,
    val confirmed: Int,
    val pending: Int,
    val rejected: Int,
    val manual: Int,
)

internal fun summarizePdfOutline(nodes: List<PdfOutlineNode>): PdfOutlineReviewSummary =
    nodes
        .flatMap { node -> listOf(node) + node.children.flatMap { child -> flattenPdfOutlineNodes(child) } }
        .fold(PdfOutlineReviewSummary(0, 0, 0, 0, 0)) { summary, node ->
            summary.copy(
                total = summary.total + 1,
                confirmed = summary.confirmed + if (node.reviewState == PdfOutlineReviewState.CONFIRMED) 1 else 0,
                pending = summary.pending + if (node.reviewState == PdfOutlineReviewState.CANDIDATE) 1 else 0,
                rejected = summary.rejected + if (node.reviewState == PdfOutlineReviewState.REJECTED) 1 else 0,
                manual = summary.manual + if (node.reviewState == PdfOutlineReviewState.MANUAL) 1 else 0,
            )
        }

private fun flattenPdfOutlineNodes(node: PdfOutlineNode): List<PdfOutlineNode> =
    node.children
        .flatMap { child -> listOf(child) + flattenPdfOutlineNodes(child) }

internal fun moveFileAtomically(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun outlineExportMatches(
    expected: List<Pair<String, Int>>,
    actual: List<Pair<String, Int>>,
    strategy: PdfOutlineExportStrategy,
): Boolean {
    val expectedCounts = expected.groupingBy { item -> item }.eachCount()
    val actualCounts = actual.groupingBy { item -> item }.eachCount()
    return when (strategy) {
        PdfOutlineExportStrategy.REPLACE -> {
            actualCounts == expectedCounts
        }

        PdfOutlineExportStrategy.APPEND -> {
            expectedCounts.all { (item, count) -> actualCounts.getOrDefault(item, 0) >= count }
        }
    }
}

internal fun outlineTreeExportMatches(
    expected: List<PdfOutlineNode>,
    actual: List<PdfNativeOutlineNode>,
    strategy: PdfOutlineExportStrategy,
): Boolean {
    val expectedShape = outlineShape(expected)
    val actualShape = nativeOutlineShape(actual)
    return when (strategy) {
        PdfOutlineExportStrategy.REPLACE -> actualShape == expectedShape
        PdfOutlineExportStrategy.APPEND -> actualShape.containsContiguous(expectedShape)
    }
}

private data class PdfOutlineShape(
    val depth: Int,
    val title: String,
    val pageIndex: Int,
)

private fun outlineShape(
    nodes: List<PdfOutlineNode>,
    depth: Int = 0,
): List<PdfOutlineShape> =
    nodes.flatMap { node ->
        listOf(PdfOutlineShape(depth, node.title, node.destination.pageIndex)) +
            outlineShape(node.children, depth + 1)
    }

private fun nativeOutlineShape(
    nodes: List<PdfNativeOutlineNode>,
    depth: Int = 0,
): List<PdfOutlineShape> =
    nodes.flatMap { node ->
        listOf(PdfOutlineShape(depth, node.title, node.pageIndex)) + nativeOutlineShape(node.children, depth + 1)
    }

private fun List<PdfOutlineShape>.containsContiguous(
    expected: List<PdfOutlineShape>,
): Boolean =
    when {
        expected.isEmpty() -> {
            true
        }

        expected.size > size -> {
            false
        }

        else -> {
            indices.any { start ->
                start + expected.size <= size && subList(start, start + expected.size) == expected
            }
        }
    }

internal fun pdfOutlineExportFailureMessage(failure: Throwable): String {
    val messages = generateSequence(failure) { cause -> cause.cause }.mapNotNull(Throwable::message).toList()
    val normalized = messages.joinToString(" ").lowercase()
    return when {
        listOf("password", "authenticate", "encrypted", "permission").any(normalized::contains) -> {
            "源 PDF 受密码或权限保护，无法写入书签。请先解除保护后重试。"
        }

        listOf("signature", "signed", "digital signature").any(normalized::contains) -> {
            "源 PDF 包含数字签名，导出新文件会使签名失效。请先确认后再导出。"
        }

        listOf("format", "syntax", "cannot open", "damaged", "corrupt", "truncated", "invalid")
            .any(normalized::contains) -> {
            "源 PDF 结构损坏或文件不完整，无法写入书签。请重新下载或修复后重试。"
        }

        else -> {
            messages.firstOrNull() ?: "无法导出 PDF 书签。"
        }
    }
}
