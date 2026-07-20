package dev.xuexue.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PdfNativePayloadCodec {
    fun decodeOutline(payload: ByteArray): List<PdfNativeOutlineNode> {
        val buffer = checkedBuffer(payload, OUTLINE_MAGIC, "outline")
        val version = buffer.int
        require(version == PAYLOAD_VERSION) { "Unsupported MuPDF outline payload version: $version." }
        val count = readCount(buffer, "outline")
        val roots = mutableListOf<MutableNativeNode>()
        val stack = mutableListOf<Pair<Int, MutableNativeNode>>()
        repeat(count) {
            val depth = buffer.int
            require(depth >= 0 && depth <= MAX_OUTLINE_DEPTH) { "MuPDF returned an invalid outline depth." }
            val page = buffer.int
            val rawTop = buffer.float
            val top = rawTop.takeIf { value -> value.isFinite() && value in 0f..1f }
            val flags = buffer.int
            val title = readUtf8(buffer, nullable = true)?.takeIf(String::isNotBlank) ?: "未命名书签"
            val uri = readUtf8(buffer, nullable = true)
            while (stack.lastOrNull()?.first?.let { level -> level >= depth } == true) stack.removeLast()
            val node = MutableNativeNode(title, page, top, uri, flags)
            stack
                .lastOrNull()
                ?.second
                ?.children
                ?.add(node) ?: roots.add(node)
            stack += depth to node
        }
        require(!buffer.hasRemaining()) { "MuPDF returned trailing outline data." }
        return roots.map(MutableNativeNode::toImmutable)
    }

    fun decodeStructuredText(payload: ByteArray): PdfStructuredTextPage {
        val buffer = checkedBuffer(payload, STRUCTURED_TEXT_MAGIC, "structured text")
        val version = buffer.int
        require(version == STRUCTURED_TEXT_PAYLOAD_VERSION) {
            "Unsupported MuPDF structured-text payload version: $version."
        }
        val originX = buffer.float
        val originY = buffer.float
        val width = buffer.float
        val height = buffer.float
        val blocks = mutableListOf<PdfStructuredTextBlock>()
        repeat(readCount(buffer, "structured-text block")) {
            val type = buffer.int
            val bounds = readAbsoluteRect(buffer)
            val lines = mutableListOf<PdfStructuredTextLine>()
            repeat(readCount(buffer, "structured-text line")) {
                val lineBounds = readAbsoluteRect(buffer)
                val vertical = buffer.get().toInt() != 0
                buffer.position(buffer.position() + 3)
                val chars = mutableListOf<PdfStructuredTextChar>()
                repeat(readCount(buffer, "structured-text character")) {
                    chars +=
                        PdfStructuredTextChar(
                            bounds = readAbsoluteRect(buffer),
                            codePoint = buffer.int,
                            size = buffer.float,
                            flags = buffer.int,
                            argb = buffer.int,
                        )
                }
                lines += PdfStructuredTextLine(lineBounds, vertical, chars)
            }
            blocks += PdfStructuredTextBlock(type, bounds, lines)
        }
        require(!buffer.hasRemaining()) { "MuPDF returned trailing structured-text data." }
        return PdfStructuredTextPage(width, height, blocks, originX, originY)
    }

    fun encodeOutlineForExport(document: PdfOutlineDocument): ByteArray {
        val flattened =
            buildList {
                document.nodes.forEach { node -> flatten(node, 0, this) }
            }
        require(flattened.size <= MAX_PAYLOAD_ITEMS) { "PDF outline export contains too many nodes." }
        val titleBytes =
            flattened.map { (node, _) ->
                node.title.toByteArray(Charsets.UTF_8).also { bytes ->
                    require(bytes.size <= MAX_STRING_BYTES) { "PDF outline title is too long." }
                }
            }
        val size =
            EXPORT_HEADER_BYTES +
                flattened.indices.sumOf { index -> EXPORT_NODE_FIXED_BYTES + Int.SIZE_BYTES + titleBytes[index].size }
        require(size <= MAX_EXPORT_BYTES) { "PDF outline export payload is too large." }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(EXPORT_MAGIC)
        buffer.putInt(PAYLOAD_VERSION)
        buffer.putInt(flattened.size)
        flattened.forEachIndexed { index, (node, depth) ->
            val bytes = titleBytes[index]
            buffer.putInt(depth)
            buffer.putInt(node.destination.pageIndex)
            buffer.putFloat(node.destination.topNormalized ?: -1f)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
        return buffer.array()
    }

    private fun checkedBuffer(
        payload: ByteArray,
        magic: Int,
        name: String,
    ): ByteBuffer {
        require(payload.size >= Int.SIZE_BYTES * 2) { "MuPDF returned an empty $name payload." }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == magic) { "MuPDF returned an invalid $name payload." }
        return buffer
    }

    private fun readCount(
        buffer: ByteBuffer,
        name: String,
    ): Int {
        val count = buffer.int
        require(count in 0..MAX_PAYLOAD_ITEMS) { "MuPDF returned an invalid $name count." }
        return count
    }

    private fun readUtf8(
        buffer: ByteBuffer,
        nullable: Boolean,
    ): String? {
        val length = buffer.int
        if (nullable && length < 0) return null
        require(
            length in 0..MAX_STRING_BYTES && buffer.remaining() >= length,
        ) { "MuPDF returned an invalid UTF-8 string." }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readAbsoluteRect(buffer: ByteBuffer): PdfAbsoluteRect =
        PdfAbsoluteRect(
            left = buffer.float,
            top = buffer.float,
            right = buffer.float,
            bottom = buffer.float,
        )

    private fun flatten(
        node: PdfOutlineNode,
        depth: Int,
        destination: MutableList<Pair<PdfOutlineNode, Int>>,
    ) {
        require(depth <= MAX_OUTLINE_DEPTH) { "PDF outline hierarchy is too deep." }
        destination += node to depth
        node.children.forEach { child -> flatten(child, depth + 1, destination) }
    }

    private data class MutableNativeNode(
        val title: String,
        val pageIndex: Int,
        val topNormalized: Float?,
        val uri: String?,
        val flags: Int,
        val children: MutableList<MutableNativeNode> = mutableListOf(),
    ) {
        fun toImmutable(): PdfNativeOutlineNode =
            PdfNativeOutlineNode(
                title,
                pageIndex,
                topNormalized,
                uri,
                flags,
                children.map(MutableNativeNode::toImmutable),
            )
    }

    private const val OUTLINE_MAGIC = 0x314C4F58
    private const val STRUCTURED_TEXT_MAGIC = 0x31545853
    private const val EXPORT_MAGIC = 0x31454C58
    private const val PAYLOAD_VERSION = 1
    private const val STRUCTURED_TEXT_PAYLOAD_VERSION = 2
    private const val EXPORT_HEADER_BYTES = 3 * Int.SIZE_BYTES
    private const val EXPORT_NODE_FIXED_BYTES = 3 * Int.SIZE_BYTES
    private const val MAX_OUTLINE_DEPTH = 64
    private const val MAX_PAYLOAD_ITEMS = 1_000_000
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024
    private const val MAX_EXPORT_BYTES = 64 * 1024 * 1024
}
