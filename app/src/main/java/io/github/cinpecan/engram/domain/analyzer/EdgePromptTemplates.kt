package io.github.cinpecan.engram.domain.analyzer

import java.util.Locale

/**
 * Single-turn user prompt templates tailored for the on-device Qwen2.5-0.5B runtime.
 * Keeping instructions inside the `user` turn preserves Qwen's default ChatML `system`
 * prompt, while selecting the prompt language from the note's script or romanized Pinyin
 * intent prevents cross-language drift and omits schedule schemas when unscheduled.
 */
object EdgePromptTemplates {
    private val CJK_SCRIPT_REGEX = Regex("""[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]""")
    private val LATIN_ALPHA_TOKEN_REGEX = Regex("""[a-z]{2,}""")

    /** Mandarin Pinyin words that do not overlap common English vocabulary. */
    internal val UNAMBIGUOUS_PINYIN_TOKENS: Set<String> = setOf(
        "wo", "women", "ni", "nimen", "tamen", "zhe", "zheli", "nali",
        "shenme", "zenme", "weishenme", "duoshao", "yao", "xiang", "chi",
        "niurou", "zhurou", "jirou", "yangrou", "naicha", "kafei", "huoguo",
        "pingguo", "niunai", "shuiguo", "mifan", "miantiao", "zaofan", "wufan", "wanfan",
        "yiqi", "wan", "wanr", "mingtian", "jintian", "zuotian", "houtian",
        "xiawu", "shangwu", "zhongwu", "wanshang", "zaoshang", "xianzai",
        "kaihui", "gongsi", "pengyou", "tongshi", "laopo", "laogong", "mama", "baba",
        "shuijiao", "gouwu", "chaoshi", "yaodian", "dianhua", "kuaidi",
        "tianqi", "leng", "suan", "jia", "jian", "qu", "mai", "zhao", "shuo",
    )

    internal val GENERAL_PINYIN_SYLLABLES: Set<String> = UNAMBIGUOUS_PINYIN_TOKENS + setOf(
        "he", "yi", "qi", "er", "san", "si", "wu", "liu", "ba", "jiu", "shi",
        "lai", "kan", "gei", "zai", "le", "de", "ma", "ne", "ya", "ta", "men",
        "dian", "hao", "bu", "mei", "you", "hen", "tai", "dou", "hai", "xian",
        "dao", "cong", "gen", "wei", "re", "shu", "shui", "cha", "fan", "cai",
    )

    fun containsCjk(text: String): Boolean = CJK_SCRIPT_REGEX.containsMatchIn(text)

    fun isRomanizedPinyin(text: String): Boolean {
        if (containsCjk(text)) return false
        val tokens = LATIN_ALPHA_TOKEN_REGEX.findAll(text.lowercase(Locale.US))
            .map { it.value }
            .toList()
        if (tokens.size < 3) return false
        val unambiguousCount = tokens.count { it in UNAMBIGUOUS_PINYIN_TOKENS }
        if (unambiguousCount >= 3) return true
        if (unambiguousCount < 2) return false
        val pinyinCount = tokens.count { it in GENERAL_PINYIN_SYLLABLES }
        return pinyinCount.toDouble() / tokens.size.toDouble() >= 0.45
    }

    fun isChineseIntent(text: String): Boolean = containsCjk(text) || isRomanizedPinyin(text)

    fun buildDistillationUserPrompt(
        content: String,
        formattedNow: String,
        hasScheduleSignal: Boolean = true,
    ): String = when {
        containsCjk(content) -> buildCjkUserPrompt(content, formattedNow)
        isRomanizedPinyin(content) -> buildPinyinUserPrompt(content, formattedNow, hasScheduleSignal)
        else -> buildEnglishUserPrompt(content, formattedNow, hasScheduleSignal)
    }

    /**
     * Escaped Simplified Chinese single-turn user prompt tailored for Qwen2.5-0.5B:
     * introduces Engram with the reference local timestamp (for relative schedule conversion only,
     * never written into the summary), asks for semicolon-separated key facts (no bullets inside
     * the prompt so the 0.5B model stays on valid JSON keys, <= 24 chars per clause, redacting
     * passwords/secrets as "see source" in Chinese), 1-3 Chinese scene tags (`[]` if none),
     * nullable `calendarProposal`, and grounded `relations` (`[]` when < 2 entities), placing the
     * compact flat JSON skeleton directly above the raw note line. All non-ASCII code points are
     * `\uXXXX`-escaped to preserve the English-only source gate.
     */
    fun buildCjkUserPrompt(
        content: String,
        formattedNow: String,
    ): String =
        "\u4F60\u662F\u5370\u8FF9\uFF08Engram\uFF09\u767D\u76D2\u8BB0\u5FC6\u52A9\u624B\u3002\u5F53\u524D\u53C2\u8003\u65F6\u95F4\uFF1A$formattedNow\uFF08\u4EC5\u7528\u4E8E\u6362\u7B97\u76F8\u5BF9\u65E5\u7A0B\uFF0C\u5207\u52FF\u5199\u5165\u6458\u8981\uFF09\u3002\n" +
            "\u8BF7\u5FE0\u5B9E\u63D0\u70BC\u4E0B\u65B9\u201C\u539F\u6587\u201D\u7684\u5168\u90E8\u5173\u952E\u4E8B\u5B9E\uFF08\u52FF\u56DE\u7B54\u63D0\u95EE\u6216\u8BA1\u7B97\u7B97\u5F0F\uFF1B\u591A\u8981\u70B9\u7528\u201C\uFF1B\u201D\u5206\u9694\uFF0C\u5355\u6761\u4E0D\u8D85\u8FC724\u5B57\uFF1B\u5BC6\u7801\u53E3\u4EE4\u8131\u654F\u4E3A\u201C\u8BE6\u89C1\u539F\u6587\u201D\uFF09\uFF0C\u63D0\u53D6 1~3\u4E2A\u4E2D\u6587\u573A\u666F\u6807\u7B7E\uFF08\u7EAF\u5B57\u7B26\u4E32\u6570\u7EC4\uFF0C\u7981\u586B\u4EBA\u540D\uFF0C\u65E0\u5408\u9002\u5206\u7C7B\u586B []\uFF09\u3002\u539F\u6587\u65E0\u660E\u786E\u65F6\u95F4\u5B89\u6392\u65F6 calendarProposal \u586B null\uFF0C\u6709\u660E\u786E\u65F6\u95F4\u5B89\u6392\u65F6\u586B {\"title\":\"\",\"startTimeIso\":\"YYYY-MM-DDTHH:MM:SS\",\"durationMinutes\":30,\"location\":null}\uFF1B\u539F\u6587\u5C11\u4E8E2\u4E2A\u771F\u5B9E\u5B9E\u4F53\u65F6 relations \u586B []\uFF0C\u6709\u591A\u4E2A\u5B9E\u4F53\u65F6\u586B [{\"source\":\"\",\"target\":\"\",\"relation\":\"\",\"bidirectional\":false}]\u3002\u4EC5\u8F93\u51FA\u5355\u4E2A\u5408\u6CD5 JSON \u5BF9\u8C61\uFF1A\n" +
            "{\"summary\":\"\",\"tags\":[],\"calendarProposal\":null,\"relations\":[]}\n" +
            "\u539F\u6587\uFF1A$content"

    fun buildPinyinUserPrompt(
        content: String,
        formattedNow: String,
        hasScheduleSignal: Boolean = false,
    ): String {
        val header = if (hasScheduleSignal) {
            "\u5F53\u524D\u53C2\u8003\u65F6\u95F4\uFF1A$formattedNow\u3002"
        } else {
            ""
        }
        val calendarRule = if (hasScheduleSignal) {
            "calendarProposal \u6362\u7B97\u4E3A {\"title\":\"\",\"startTimeIso\":\"YYYY-MM-DDTHH:MM:SS\",\"durationMinutes\":30,\"location\":null}"
        } else {
            "calendarProposal \u586B null"
        }
        return "$header\u8BF7\u5C06\u4E0B\u65B9\u6C49\u8BED\u62FC\u97F3\u7B14\u8BB0\u76F4\u63A5\u8FD8\u539F\u4E3A\u7B80\u77ED\u81EA\u7136\u7684\u4E2D\u6587\u8981\u70B9\uFF08\u5982\u201Cwo xiang he kafei\u201D\u8FD8\u539F\u4E3A\u201C\u6211\u60F3\u559D\u5496\u5561\u201D\uFF1B\u4FDD\u7559 ww\u3001yy \u7B49\u5B57\u6BCD\u4EE3\u79F0\u539F\u6837\uFF0C\u591A\u8981\u70B9\u7528\u201C\uFF1B\u201D\u5206\u9694\uFF09\uFF0C\u63D0\u53D6 1~2\u4E2A\u4E2D\u6587\u6807\u7B7E\uFF0C$calendarRule\uFF0C\u63D0\u53D6\u5B9E\u4F53\u5173\u7CFB\u3002\u4EC5\u8F93\u51FA\u5355\u4E2A JSON \u5BF9\u8C61\uFF1A\n" +
            "{\"summary\":\"\",\"tags\":[],\"calendarProposal\":null,\"relations\":[]}\n" +
            "\u62FC\u97F3\u539F\u6587\uFF1A$content"
    }

    fun buildEnglishUserPrompt(
        content: String,
        formattedNow: String,
        hasScheduleSignal: Boolean = true,
    ): String = if (hasScheduleSignal) {
        "You are Engram, a white-box memory assistant. Reference time: $formattedNow (for relative schedule conversion only; never copy into summary).\n" +
            "Faithfully distill ALL key facts from the raw note below into summary (keep exact entity names and numbers; separate multiple points with \"; \", <= 14 words per point; redact secrets as \"see source\"), and extract 1-3 short English topic tags (plain string array, no person names or objects, [] if none fit). " +
            "Fill calendarProposal using the reference time, and set relations to [] if fewer than 2 entities appear, otherwise [{\"source\":\"\",\"target\":\"\",\"relation\":\"\",\"bidirectional\":false}] with source != target. " +
            "Output strictly ONE valid JSON object only (no Markdown fences or commentary):\n" +
            "{\"summary\":\"\",\"tags\":[],\"calendarProposal\":{\"title\":\"\",\"startTimeIso\":\"YYYY-MM-DDTHH:MM:SS\",\"durationMinutes\":30,\"location\":null},\"relations\":[]}\n" +
            "Raw note: $content"
    } else {
        "You are Engram, a white-box memory assistant.\n" +
            "Faithfully distill ALL key facts from the raw note below into summary (smooth obvious typos, keep exact entity names and numbers, never answer questions or compute math; separate multiple points with \"; \", <= 14 words per point; redact passwords or secrets as \"see source\"), and extract 1-3 short English topic tags (plain string array, no person names or objects, [] if none fit). " +
            "Always set calendarProposal to null. " +
            "Set relations to [] if fewer than 2 entities appear, otherwise [{\"source\":\"\",\"target\":\"\",\"relation\":\"\",\"bidirectional\":false}] with source != target. " +
            "Output strictly ONE valid JSON object only (no Markdown fences or commentary):\n" +
            "{\"summary\":\"\",\"tags\":[],\"calendarProposal\":null,\"relations\":[]}\n" +
            "Raw note: $content"
    }
}
