package jp.oif.tsunaminavi.offline

/** Accept only a single known function call with exactly the expected arguments. */
object FunctionCallParser {
    private val call = Regex("""call:(\w+)\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
    private val mobility = Regex("""\s*mobility\s*:\s*<escape>(normal|slow|wheelchair)<escape>\s*""")
    private val noArgs = setOf("ask_floor", "road_blocked", "lost_family", "injured", "when_to_return", "repeat", "other")

    fun toId(output: String): String? {
        val matches = call.findAll(output).toList()
        if (matches.size != 1) return null
        val name = matches[0].groupValues[1]
        val args = matches[0].groupValues[2]
        if (name == "reselect") {
            return when (mobility.matchEntire(args)?.groupValues?.get(1)) {
                "normal" -> "reselect"
                "slow" -> "reselect_slow"
                "wheelchair" -> "reselect_wheelchair"
                else -> null
            }
        }
        return name.takeIf { it in noArgs && args.isBlank() }
    }
}
