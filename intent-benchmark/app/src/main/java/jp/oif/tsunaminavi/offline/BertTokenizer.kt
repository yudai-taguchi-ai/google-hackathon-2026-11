package jp.oif.tsunaminavi.offline

/** WordPiece tokenizer for the cased multilingual BERT vocabulary. */
class BertTokenizer(vocabulary: List<String>) {
    private val ids = vocabulary.withIndex().associate { it.value to it.index }
    private val unknown = requireNotNull(ids["[UNK]"])
    private val start = requireNotNull(ids["[CLS]"])
    private val end = requireNotNull(ids["[SEP]"])

    fun encode(text: String, maxLength: Int = 128): LongArray {
        require(maxLength >= 2)
        val basicTokens = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                basicTokens.add(current.toString())
                current.clear()
            }
        }
        text.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            when {
                cp == 0 || cp == 0xfffd || (Character.isISOControl(cp) && cp !in listOf(9, 10, 13)) || type == Character.FORMAT.toInt() -> Unit
                Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() -> flush()
                isChinese(cp) || isPunctuation(cp, type) -> {
                    flush()
                    basicTokens.add(String(Character.toChars(cp)))
                }
                else -> current.appendCodePoint(cp)
            }
        }
        flush()
        val result = mutableListOf(start.toLong())
        for (token in basicTokens) {
            val points = token.codePoints().toArray()
            val pieces = mutableListOf<Int>()
            var offset = 0
            if (points.size <= 100) {
                while (offset < points.size) {
                    var boundary = points.size
                    var match: Int? = null
                    while (boundary > offset) {
                        val piece = (if (offset > 0) "##" else "") + String(points, offset, boundary - offset)
                        match = ids[piece]
                        if (match != null) break
                        boundary--
                    }
                    if (match == null) break
                    pieces.add(match)
                    offset = boundary
                }
            }
            if (offset != points.size) {
                pieces.clear()
                pieces.add(unknown)
            }
            for (piece in pieces) {
                if (result.size == maxLength - 1) break
                result.add(piece.toLong())
            }
            if (result.size == maxLength - 1) break
        }
        result.add(end.toLong())
        return result.toLongArray()
    }

    private fun isChinese(cp: Int): Boolean = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
        cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
        cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f

    private fun isPunctuation(cp: Int, type: Int): Boolean = cp in 33..47 || cp in 58..64 ||
        cp in 91..96 || cp in 123..126 || type in listOf(
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )
}
