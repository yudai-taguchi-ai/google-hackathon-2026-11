package jp.oif.tsunaminavi.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FunctionCallParserTest {
    @Test fun acceptsOnlyKnownCalls() {
        assertEquals("reselect", FunctionCallParser.toId("call:reselect{mobility:<escape>normal<escape>}"))
        assertEquals("reselect_slow", FunctionCallParser.toId("<start_function_call>call:reselect{mobility:<escape>slow<escape>}<end_function_call>"))
        assertEquals("reselect_wheelchair", FunctionCallParser.toId("call:reselect{mobility:<escape>wheelchair<escape>}"))
        assertEquals("road_blocked", FunctionCallParser.toId("call:road_blocked{}"))
        assertNull(FunctionCallParser.toId("call:reselect{}"))
        assertNull(FunctionCallParser.toId("call:reselect{mobility:<escape>wrong<escape>}"))
        assertNull(FunctionCallParser.toId("call:reselect{mobility:<escape>slow<escape>,other:<escape>x<escape>}"))
        assertNull(FunctionCallParser.toId("call:injured{x:<escape>y<escape>}"))
        assertNull(FunctionCallParser.toId("call:injured{} call:other{}"))
        assertNull(FunctionCallParser.toId("garbled output"))
    }
}
