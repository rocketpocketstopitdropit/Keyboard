package com.example.customkeyboard

/**
 * A hand-built approximation of the classic (pre-iOS 17) iPhone predictive
 * model, as its original author (Ken Kocienda) has described it: a
 * statistical word-frequency model plus short word-sequence context,
 * combined with a keyboard-aware touch model (see Autocorrector). There's
 * no tooling here to train this on a real text corpus, so it isn't
 * corpus-derived — but it follows the same shape: word frequency modeled
 * via Zipf's law (frequency falls off as 1/rank, the real mathematical
 * pattern word frequency follows in natural language) over a hand-ranked
 * word list, plus a hand-curated table of "previous word -> likely next
 * word" pairs for the words with the most distinctive continuations.
 */
object WordPredictor {

    // Ranked roughly by everyday-texting frequency, most common first.
    val WORDS: List<String> = listOf(
        "the","i","to","a","you","and",
        "is","in","it","of","that","for",
        "on","have","this","are","not","be",
        "with","was","but","just","so","my",
        "we","can","do","what","your","if",
        "will","no","get","up","out","don't",
        "like","know","me","at","there","all",
        "about","one","time","when","she","he",
        "they","as","or","some","would","from",
        "by","them","then","him","her","how",
        "now","see","been","which","who","its",
        "our","us","did","want","need","think",
        "going","got","good","really","because","here",
        "too","were","much","more","most","any",
        "other","than","these","those","into","over",
        "after","before","back","also","way","well",
        "even","new","first","last","people","day",
        "year","come","came","gonna","wanna","gotta",
        "let","let's","go","has","make","made",
        "take","took","give","gave","find","found",
        "tell","told","ask","asked","look","looked",
        "use","used","work","worked","call","called",
        "try","tried","feel","felt","seem","seemed",
        "leave","left","put","mean","meant","keep",
        "kept","start","started","show","showed","hear",
        "heard","play","played","run","move","live",
        "believe","bring","brought","happen","happened","write",
        "sit","stand","lose","lost","pay","paid",
        "meet","met","include","continue","set","learn",
        "change","lead","understand","watch","follow","stop",
        "create","speak","spoke","read","allow","add",
        "spend","spent","grow","grew","open","walk",
        "win","won","offer","remember","love","consider",
        "appear","buy","bought","wait","serve","die",
        "send","sent","expect","build","stay","fall",
        "fell","cut","reach","kill","raise","pass",
        "sell","sold","decide","return","explain","hope",
        "develop","carry","break","broke","receive","agree",
        "support","hit","produce","eat","cover","catch",
        "caught","draw","choose","chose","thanks","thank",
        "hello","hi","hey","sorry","please","okay",
        "ok","yes","yeah","yep","nope","alright",
        "cool","awesome","great","nice","fine","sure",
        "maybe","actually","probably","definitely","totally","honestly",
        "literally","basically","exactly","seriously","right","wrong",
        "true","false","best","worst","favorite","hate",
        "happy","sad","angry","tired","excited","worried",
        "scared","fun","boring","interesting","important","different",
        "difficult","easy","hard","simple","possible","impossible",
        "free","busy","ready","late","early","soon",
        "today","tomorrow","yesterday","tonight","morning","afternoon",
        "evening","night","week","weekend","month","minute",
        "second","home","house","school","office","room",
        "car","phone","message","text","email","picture",
        "photo","video","music","movie","game","book",
        "food","water","coffee","tea","dinner","lunch",
        "breakfast","family","friend","friends","person","kid",
        "kids","baby","man","woman","guy","girl",
        "boy","world","country","city","place","area",
        "street","road","door","window","table","chair",
        "bed","kitchen","bathroom","store","shop","money",
        "price","cost","job","business","company","project",
        "meeting","plan","idea","problem","question","answer",
        "reason","result","point","part","kind","type",
        "thing","stuff","everyone","everybody","everything","somebody",
        "someone","something","somewhere","anybody","anyone","anything",
        "anywhere","nobody","nothing","nowhere","both","each",
        "few","many","several","little","big","small",
        "large","long","short","high","low","old",
        "young","next","same","own","certain","able",
        "available","likely","clear","full","whole","real",
        "actual","current","recent","future","past","present",
        "local","national","public","private","personal","social",
        "political","economic","financial","physical","mental","emotional",
        "medical","technical","scientific","natural","normal","special",
        "specific","general","common","rare","strange","weird",
        "funny","serious","calm","quiet","loud","fast",
        "slow","hot","cold","warm","dry","wet",
        "clean","dirty","safe","dangerous","healthy","sick",
        "strong","weak","rich","poor","heavy","light",
        "dark","bright","beautiful","ugly","pretty","handsome",
        "tall","am","should","said","sounds","care",
        "trying","wanted","worries","course","case","least",
        "luck","birthday","name"
    )

    // Zipf's law: frequency(word) ~ 1 / rank. Real word frequencies in any
    // language follow this curve closely — a few words are extremely
    // common, and frequency drops off sharply after that.
    private val frequency: Map<String, Double> by lazy {
        WORDS.withIndex().associate { (i, w) -> w to 1.0 / (i + 1) }
    }

    fun frequencyOf(word: String): Double = frequency[word.lowercase()] ?: 0.0

    // The bigram half of the model: only words with a genuinely
    // distinctive next-word pattern are listed — everything else just
    // falls back to plain word frequency, which is the honest,
    // conservative thing to do when there's no strong signal either way.
    private val bigrams: Map<String, List<Pair<String, Double>>> = mapOf(
        "i" to listOf("am" to 1.0,"have" to 0.9,"think" to 0.8,"was" to 0.75,"want" to 0.7,"need" to 0.65,"don't" to 0.6,"just" to 0.55,"love" to 0.5),
        "you" to listOf("are" to 1.0,"know" to 0.8,"can" to 0.75,"want" to 0.6,"have" to 0.6,"should" to 0.5,"just" to 0.45),
        "we" to listOf("are" to 1.0,"can" to 0.8,"should" to 0.7,"have" to 0.65,"need" to 0.6,"will" to 0.55,"were" to 0.5),
        "they" to listOf("are" to 1.0,"were" to 0.7,"have" to 0.6,"can" to 0.5,"will" to 0.45),
        "he" to listOf("is" to 1.0,"was" to 0.8,"has" to 0.6,"said" to 0.5),
        "she" to listOf("is" to 1.0,"was" to 0.8,"has" to 0.6,"said" to 0.5),
        "it" to listOf("is" to 1.0,"was" to 0.85,"will" to 0.5,"just" to 0.4),
        "that" to listOf("is" to 1.0,"was" to 0.6,"would" to 0.5,"sounds" to 0.4),
        "this" to listOf("is" to 1.0,"was" to 0.5,"one" to 0.4),
        "don't" to listOf("know" to 1.0,"want" to 0.8,"think" to 0.7,"have" to 0.6,"need" to 0.5,"care" to 0.4),
        "can't" to listOf("wait" to 1.0,"believe" to 0.7,"find" to 0.5,"stop" to 0.4),
        "i'm" to listOf("not" to 1.0,"going" to 0.9,"sorry" to 0.8,"just" to 0.7,"so" to 0.6,"trying" to 0.5),
        "let's" to listOf("go" to 1.0,"just" to 0.6,"do" to 0.5,"see" to 0.4,"try" to 0.4),
        "going" to listOf("to" to 1.0),
        "want" to listOf("to" to 1.0),
        "need" to listOf("to" to 1.0),
        "have" to listOf("to" to 1.0,"a" to 0.7,"been" to 0.5),
        "has" to listOf("to" to 0.8,"been" to 0.6,"a" to 0.5),
        "to" to listOf("be" to 0.9,"go" to 0.8,"do" to 0.75,"get" to 0.7,"see" to 0.6,"know" to 0.55,"make" to 0.5,"take" to 0.45),
        "thank" to listOf("you" to 1.0),
        "thanks" to listOf("for" to 0.6,"so" to 0.5),
        "sorry" to listOf("for" to 0.6,"about" to 0.5),
        "how" to listOf("are" to 1.0,"was" to 0.7,"do" to 0.6,"is" to 0.5,"much" to 0.4),
        "what" to listOf("is" to 1.0,"are" to 0.8,"do" to 0.7,"time" to 0.5,"about" to 0.4),
        "are" to listOf("you" to 1.0,"we" to 0.5,"they" to 0.5),
        "is" to listOf("it" to 0.7,"that" to 0.6,"this" to 0.5),
        "just" to listOf("got" to 0.6,"want" to 0.5,"need" to 0.5,"wanted" to 0.4,"in" to 0.4),
        "so" to listOf("much" to 0.6,"good" to 0.5,"happy" to 0.4,"sorry" to 0.4),
        "really" to listOf("good" to 0.5,"want" to 0.5,"like" to 0.4,"need" to 0.4),
        "no" to listOf("problem" to 0.7,"worries" to 0.5,"way" to 0.4),
        "for" to listOf("the" to 0.6,"you" to 0.6,"your" to 0.5,"me" to 0.4),
        "of" to listOf("the" to 0.9,"course" to 0.5),
        "in" to listOf("the" to 0.7,"a" to 0.5,"case" to 0.3),
        "at" to listOf("the" to 0.6,"least" to 0.4,"home" to 0.4),
        "on" to listOf("the" to 0.6,"my" to 0.5,"time" to 0.4),
        "see" to listOf("you" to 1.0,"if" to 0.5,"what" to 0.4),
        "talk" to listOf("to" to 0.8,"soon" to 0.6),
        "good" to listOf("morning" to 0.6,"night" to 0.6,"luck" to 0.4,"idea" to 0.4),
        "happy" to listOf("birthday" to 0.7,"to" to 0.4),
        "my" to listOf("name" to 0.4,"phone" to 0.3,"friend" to 0.3),
        "your" to listOf("name" to 0.3,"phone" to 0.3),
        "did" to listOf("you" to 1.0,"it" to 0.4),
        "will" to listOf("be" to 0.8,"you" to 0.6,"it" to 0.4),
        "would" to listOf("you" to 0.8,"like" to 0.6,"be" to 0.5),
        "could" to listOf("you" to 0.8,"be" to 0.5),
        "should" to listOf("be" to 0.6,"have" to 0.5,"i" to 0.5),
        "please" to listOf("let" to 0.5,"call" to 0.4,"send" to 0.4)
    )

    /**
     * Best whole-word candidate for what's being typed, combining word
     * frequency with the previous word's likely continuations. Returns
     * null if nothing in the list matches the prefix at all.
     */
    fun predictNextWord(previousWord: String, prefix: String): String? {
        val lowerPrefix = prefix.lowercase()
        val continuations = bigrams[previousWord.lowercase()].orEmpty().toMap()

        var best: String? = null
        var bestScore = -1.0
        for (word in WORDS) {
            if (word.length <= lowerPrefix.length || !word.startsWith(lowerPrefix)) continue
            val bigramBoost = continuations[word] ?: 0.0
            val score = frequencyOf(word) + bigramBoost * 2.0
            if (score > bestScore) {
                bestScore = score
                best = word
            }
        }
        return best
    }

    fun predictNextChar(previousWord: String, prefix: String): Char? {
        val word = predictNextWord(previousWord, prefix) ?: return null
        return word.getOrNull(prefix.length)
    }
}
