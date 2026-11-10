package jp.oif.tsunaminavi.offline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

class BertTokenizerTest {
    @Test
    fun wordPieceAndTruncation() {
        val tokenizer = BertTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "Hello", "world", "##s", "!"))
        assertContentEquals(longArrayOf(2, 4, 5, 6, 7, 3), tokenizer.encode("Hello worlds!"))
        assertContentEquals(longArrayOf(2, 1, 3), tokenizer.encode("missing"))
        assertContentEquals(longArrayOf(2, 4, 5, 3), tokenizer.encode("Hello worlds!", 4))
    }

    @Test
    fun matchesMultilingualModelVocabulary() {
        val path = System.getenv("MBERT_VOCAB") ?: return
        val tokenizer = BertTokenizer(File(path).readLines(Charsets.UTF_8))
        val cases = listOf(
            "Not here, no! Another place, quick, where else?!" to longArrayOf(101, 16040, 19353, 117, 10192, 106, 17101, 11192, 117, 69609, 117, 10940, 40843, 136, 106, 102),
            "別んとこ！ここ無理入れない！" to longArrayOf(101, 2550, 1981, 11662, 28442, 10055, 1919, 28442, 5318, 5515, 2446, 1976, 15355, 10055, 102),
            "太挤了进不去换地方快点换别的" to longArrayOf(101, 3199, 4112, 2146, 7701, 2080, 2724, 4135, 3035, 4335, 3806, 5286, 4135, 2553, 5718, 102),
            "사람 너무 꽉 찼어 딴 데 다른 데 어디야 빨리!" to longArrayOf(101, 9405, 61250, 9004, 32537, 100, 100, 9132, 9083, 19709, 9083, 9546, 48446, 21711, 9389, 12692, 106, 102),
        )
        cases.forEach { (text, expected) -> assertContentEquals(expected, tokenizer.encode(text), text) }
    }
}
