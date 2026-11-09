package jp.oif.tsunaminavi.offline

/** 話した言葉 → アプリの頼みごと（id）。圏外で使う。FunctionGemma の学習が済んだら、この形のまま差し替える。 */
fun interface IntentClassifier {
    fun classify(text: String): String
}

/**
 * キーワードで振り分ける方法（backend/finetune/score.py の比べる相手と同じ規則）。テスト 400 件で 70.5%。
 * 当てはまらない時は "other" を返し、画面のボタンで選んでもらう。
 */
object KeywordIntent : IntentClassifier {
    private val RULES = listOf(
        "reselect_wheelchair" to "wheelchair|stroller|pram|cane|stairs|車いす|車椅子|ベビーカー|杖|階段|轮椅|婴儿车|楼梯|휠체어|유모차|계단",
        "reselect_slow" to "can'?t (walk|run)|cannot (walk|run)|slow|elderly|old|child|kid|baby|pregnan|走れ|歩けな|遅い|子ども|子供|高齢|妊娠|走不|老人|孩子|慢|못 (걷|뛰)|느려|아이|노인|임신",
        "road_blocked" to "block|closed|flood|can'?t pass|ふさが|塞が|通れ|冠水|堵|过不去|淹|막혔|못 지나|침수",
        "ask_floor" to "floor|how high|upstairs|何階|どこまで上|几楼|多高|몇 층|얼마나 높",
        "lost_family" to "lost|can'?t find|separated|missing|はぐれ|見つから|いない|走散|找不到|잃어|못 찾|헤어",
        "injured" to "injur|hurt|bleed|blood|pain|sick|けが|怪我|血|痛|具合|受伤|流血|疼|다쳤|피|아파",
        "when_to_return" to "go back|return|hotel|luggage|how long|戻|帰|荷物|いつまで|回去|行李|多久|돌아가|짐|언제까지",
        "repeat" to "again|repeat|didn'?t (hear|understand)|pardon|もう一度|聞こえ|分から|再说|没听|다시|못 들",
        "reselect" to "another|other place|somewhere else|different|full|crowded|別の|他の|混ん|满|别的|其他|다른|붐",
    ).map { (id, pattern) -> id to Regex(pattern) }

    override fun classify(text: String): String {
        val low = text.lowercase()
        return RULES.firstOrNull { (_, re) -> re.containsMatchIn(low) }?.first ?: "other"
    }
}
