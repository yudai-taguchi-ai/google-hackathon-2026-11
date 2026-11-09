package jp.oif.tsunaminavi.offline

import kotlin.test.Test
import kotlin.test.assertEquals

class IntentClassifierTest {
    @Test
    fun 言い方から頼みごと() {
        assertEquals("reselect_slow", KeywordIntent.classify("My child can't run"))
        assertEquals("reselect_wheelchair", KeywordIntent.classify("I'm in a wheelchair"))
        assertEquals("injured", KeywordIntent.classify("けがをしました"))
        assertEquals("other", KeywordIntent.classify("nice weather today"))
    }
}
