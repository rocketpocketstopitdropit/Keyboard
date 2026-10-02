package com.example.customkeyboard

import android.content.Context
import kotlin.math.ln
import kotlin.math.pow

/**
 * Word model with three levels, blended together:
 *
 *  - word frequency (assets/wordlist.txt, or a Zipf-ranked built-in list
 *    if that file is missing),
 *  - word pairs: what follows the last word (assets/ngrams.txt, mixed with
 *    a small hand-curated table),
 *  - word triples: what follows the last two words (assets/ngrams.txt),
 *
 * plus what the keyboard has learned from the person's own typing (see
 * [UserLanguageModel]).
 *
 * "Previous" arguments are a context of up to two words, oldest first
 * ("how are", "<s> i"); build it with [pushContext]. A single word or ""
 * still works. For a word w after context (p2, p1):
 *
 *   unigram(w)    = (1 - a) * prior(w) + a * userFrequency(w)
 *   pair(w|p1)    = (1 - b) * corpus(w|p1) + b * userPair(w|p1)
 *   pairMix(w)    = L2 * pair(w|p1) + (1 - L2) * unigram(w)
 *   P(w | p2 p1)  = L3 * triple(w|p2 p1) + (1 - L3) * pairMix(w)
 *
 * a and b grow as the person types more. L2 and L3 come from how much
 * corpus data there is for that context (Witten-Bell: seen / (seen +
 * distinct followers)), so a well-attested context leans on its data and
 * a rare one falls back to the level below. L3 also shrinks as the
 * person's own habits after p1 build up, so personal patterns still win.
 * Corpus n-grams load on a background thread; until they arrive (or if
 * the file is missing) the curated pair table is used on its own.
 *
 * assets/wordlist.txt: one word per line, most common first, with an
 * optional tab-separated Zipf value (log10 of uses per billion words, e.g.
 * "the\t7.73"). With Zipf values those real frequencies are the prior;
 * built-in words and the extra lists (wordlist2.txt, wordlist3.txt,
 * wordlist4.txt) are added at the rarest listed frequency. Without Zipf
 * values everything is ranked after the built-in words, as before.
 * Built from wordfreq (CC BY-SA 4.0); see assets/WORDLIST_LICENSE.txt.
 *
 * assets/ngrams.txt (tab-separated), counted from real conversations (see
 * assets/NGRAMS_LICENSE.txt):
 *   C <context> <total> <distinct>  how much followed a context, and how many different words
 *   B <word> <next> <count>         word pair
 *   T <w1 w2> <next> <count>        word triple
 */
object WordPredictor {
    // Only real words are ever predicted or suggested: the built-in dictionary,
    // plus words the person has deliberately added. Typing a word that isn't in
    // either never teaches the keyboard that word.

    /** Previous-word marker for "start of a message or sentence". */
    const val START = "<s>"

    private const val MAX_WORD_LEN = 24
    private const val BIGRAM_WEIGHT = 0.7
    private const val MAX_USER_UNIGRAM_WEIGHT = 0.6
    private const val UNIGRAM_TRUST_SCALE = 300.0
    private const val MAX_USER_BIGRAM_WEIGHT = 0.6
    private const val BIGRAM_TRUST_SCALE = 10.0
    /** Share of a word pair's estimate given to the hand-curated table when corpus data exists too. */
    private const val CURATED_SHARE = 0.3
    private const val MAX_CONTEXT_WEIGHT = 0.9
    private const val FLOOR = 1e-7

    /** Words the person added have no frequency rank, so they get a fair mid-list one. */
    private const val ADDED_WORD_RANK = 1500

    /** A highlighted key must be at least this likely, or nothing is highlighted. */
    private const val MIN_CONFIDENCE = 0.3

    private val LN_FLOOR = ln(1e-6)
    private val LN_CEIL = ln(0.2)

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
        "luck","birthday","name",
        // Contractions, so they can be predicted and never "corrected" away.
        "i'm","can't","it's","that's","i'll","i've","i'd","we're","they're","you're",
        "won't","didn't","doesn't","isn't","wasn't","couldn't","wouldn't","shouldn't",
        "haven't","hasn't","aren't","weren't","what's","there's","he's","she's","who's","how's",
        // Texting staples that are real words to the people typing them.
        "their","yo","ya","lol","omg","btw","idk","tbh","brb","imo","asap","wow","ugh","haha",
        "lmao","yay","oops","dude","bro","mom","dad","babe","cuz","tho","thru","ima"
    )

    // The bigram half of the model: only words with a genuinely
    // distinctive next-word pattern are listed — everything else just
    // falls back to plain word frequency, which is the honest,
    // conservative thing to do when there's no strong signal either way.
    private val bigrams: Map<String, List<Pair<String, Double>>> = mapOf(
        // START = the beginning of a message or sentence.
        "<s>" to listOf("i" to 1.0,"the" to 0.6,"hey" to 0.6,"thanks" to 0.5,"what" to 0.5,"how" to 0.5,"yeah" to 0.5,"ok" to 0.4,"hi" to 0.4,"we" to 0.4,"lol" to 0.3,"can" to 0.3,"do" to 0.3,"thank" to 0.3,"good" to 0.3),
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

    // ---------- state ----------

    private var appContext: Context? = null
    private val user = UserLanguageModel()

    private var staticWords: List<String> = WORDS
    private var rank: Map<String, Int> = buildRank(WORDS)
    private var harmonic: Double = harmonicSum(WORDS.size)

    /** Real word frequencies (summing to 1) from wordlist.txt, or null to use rank. */
    private var staticFreq: Map<String, Double>? = null

    /** Prior for a word the person added, when real frequencies are in use. */
    private var addedWordFreq = 0.0

    /** Word pairs and triples counted from real conversations. */
    private class NgramTables(
        val pairs: Map<String, Map<String, Float>>,
        val triples: Map<String, Map<String, Float>>,
        /** context -> [total count seen after it, number of different words seen after it] */
        val contexts: Map<String, FloatArray>
    )

    /** Filled in by a background thread once assets/ngrams.txt is read. */
    @Volatile
    private var ngrams: NgramTables? = null

    /** Every usable word, sorted (so a prefix is one contiguous run) and bucketed by length. */
    private class VocabIndex(val sorted: Array<String>, val byLength: Array<List<String>>)

    private var index: VocabIndex? = null
    private var indexVersion = -1

    /** Call once from the keyboard service or settings; safe to call again. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        loadAssetWords(app)
        user.load(app)
        // Earlier versions also remembered words that aren't real words; forget those.
        user.retainWords { it == START || isKnown(it) }
        index = null
        loadNgramsInBackground(app)
    }

    private fun loadNgramsInBackground(context: Context) {
        Thread({
            try {
                val pairs = HashMap<String, HashMap<String, Float>>(8192)
                val triples = HashMap<String, HashMap<String, Float>>(16384)
                val contexts = HashMap<String, FloatArray>(32768)
                // Share one String object per distinct word to save memory.
                val pool = HashMap<String, String>(32768)
                fun shared(s: String): String = pool.getOrPut(s) { s }

                context.assets.open("ngrams.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val parts = line.split('\t')
                        if (parts.size != 4) continue
                        when (parts[0]) {
                            "C" -> {
                                val total = parts[2].toFloatOrNull() ?: continue
                                val distinct = parts[3].toFloatOrNull() ?: continue
                                contexts[shared(parts[1])] = floatArrayOf(total, distinct)
                            }
                            "B", "T" -> {
                                val count = parts[3].toFloatOrNull() ?: continue
                                val table = if (parts[0] == "B") pairs else triples
                                table.getOrPut(shared(parts[1])) { HashMap() }[shared(parts[2])] = count
                            }
                        }
                    }
                }
                ngrams = NgramTables(pairs, triples, contexts)
            } catch (e: Exception) {
                // No file or a damaged one: keep using the curated pairs.
            }
        }, "ngram-loader").start()
    }

    private fun buildRank(words: List<String>): Map<String, Int> {
        val m = HashMap<String, Int>(words.size * 2)
        for ((i, w) in words.withIndex()) if (!m.containsKey(w)) m[w] = i
        return m
    }

    private fun harmonicSum(n: Int): Double {
        var s = 0.0
        for (i in 1..n) s += 1.0 / i
        return s
    }

    private fun readList(context: Context, name: String): List<String> =
        try {
            context.assets.open(name).bufferedReader().use { it.readLines() }
        } catch (e: Exception) {
            emptyList() // A missing file just means fewer words.
        }

    private fun loadAssetWords(context: Context) {
        // wordlist.txt: word -> frequency (uses per word of text), or null if the line had no Zipf value.
        val listed = LinkedHashMap<String, Double?>()
        for (line in readList(context, "wordlist.txt")) {
            val w = normalize(line.substringBefore('\t').trim())
            if (!isLearnable(w) || listed.containsKey(w)) continue
            val zipf = line.substringAfter('\t', "").trim().toDoubleOrNull()
            listed[w] = zipf?.let { 10.0.pow(it - 9.0) }
        }
        // Extra lists without frequencies, most common first.
        val extras = LinkedHashSet<String>()
        for (name in listOf("wordlist2.txt", "wordlist3.txt", "wordlist4.txt")) {
            for (line in readList(context, name)) {
                val w = normalize(line.substringBefore('\t').trim())
                if (isLearnable(w)) extras.add(w)
            }
        }

        val rarest = listed.values.filterNotNull().minOrNull()
        if (rarest == null) {
            // No frequencies: rank everything after the built-in words, as before.
            val merged = LinkedHashSet<String>(WORDS)
            merged.addAll(listed.keys)
            merged.addAll(extras)
            staticWords = merged.toList()
            staticFreq = null
        } else {
            // Real frequencies: wordlist.txt's order wins; built-in and extra words it
            // lacks (and lines missing a value) get the rarest listed frequency.
            val freq = LinkedHashMap<String, Double>(listed.size + WORDS.size + extras.size)
            for ((w, f) in listed) freq[w] = f ?: rarest
            for (w in WORDS) if (!freq.containsKey(w)) freq[w] = rarest
            for (w in extras) if (!freq.containsKey(w)) freq[w] = rarest
            val sum = freq.values.sum()
            for (e in freq.entries) e.setValue(e.value / sum)
            staticWords = freq.keys.toList()
            staticFreq = freq
            // Added words get the frequency of a fairly common word, like their old mid-list rank.
            addedWordFreq = freq[staticWords[minOf(ADDED_WORD_RANK, staticWords.size - 1)]] ?: 0.0
        }
        rank = buildRank(staticWords)
        harmonic = harmonicSum(staticWords.size)
    }

    // ---------- text hygiene ----------

    fun normalize(word: String): String = word.lowercase().replace('’', '\'')

    /**
     * The context after [word] is typed following [context]: the last two
     * words, oldest first. Keep passing the result back in as "previous".
     */
    fun pushContext(context: String, word: String): String {
        val w = normalize(word).trim()
        if (w.isEmpty()) return context
        val last = lastWord(context)
        return if (last.isEmpty()) w else "$last $w"
    }

    /** The most recent word of a context ("how are" -> "are"). */
    private fun lastWord(context: String): String =
        normalize(context).trim().substringAfterLast(' ')

    /** The word before the most recent one, or "" if there isn't one. */
    private fun wordBeforeLast(context: String): String {
        val t = normalize(context).trim()
        val i = t.lastIndexOf(' ')
        return if (i < 0) "" else t.substring(0, i).substringAfterLast(' ')
    }

    /** Only plain words (letters, with an inner apostrophe) are worth keeping. */
    private fun isLearnable(w: String): Boolean {
        if (w.isEmpty() || w.length > MAX_WORD_LEN) return false
        for ((i, c) in w.withIndex()) {
            if (c == '\'') {
                if (i == 0 || i == w.length - 1) return false
            } else if (!c.isLetter()) {
                return false
            }
        }
        // Three of the same letter in a row ("sooo") is emphasis, not a word.
        for (i in 2 until w.length) {
            if (w[i] == w[i - 1] && w[i] == w[i - 2]) return false
        }
        return true
    }

    // ---------- vocabulary ----------

    private fun vocabIndex(): VocabIndex {
        val current = index
        if (current != null && indexVersion == user.vocabularyVersion) return current
        val merged = ArrayList<String>(staticWords)
        for (w in user.addedWordList()) if (!rank.containsKey(w)) merged.add(w)
        val sorted = merged.toTypedArray()
        sorted.sort()
        var maxLen = 0
        for (w in merged) if (w.length > maxLen) maxLen = w.length
        val buckets = Array(maxLen + 1) { ArrayList<String>() }
        for (w in merged) buckets[w.length].add(w)
        val built = VocabIndex(sorted, Array<List<String>>(maxLen + 1) { i -> buckets[i] })
        index = built
        indexVersion = user.vocabularyVersion
        return built
    }

    /** Every usable word that is exactly [length] letters long. */
    fun wordsOfLength(length: Int): List<String> {
        val idx = vocabIndex()
        return if (length >= 0 && length < idx.byLength.size) idx.byLength[length] else emptyList()
    }

    /** Position of the first sorted word that is not before [prefix]; words with the prefix start there. */
    private fun firstWithPrefix(idx: VocabIndex, prefix: String): Int {
        var lo = 0
        var hi = idx.sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (idx.sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** True if at least one usable word starts with [prefix]. */
    fun continues(prefix: String): Boolean {
        val idx = vocabIndex()
        val p = normalize(prefix)
        val i = firstWithPrefix(idx, p)
        return i < idx.sorted.size && idx.sorted[i].startsWith(p)
    }

    /** A real word: in the built-in dictionary, or one the person added. */
    fun isKnown(word: String): Boolean {
        val w = normalize(word)
        return rank.containsKey(w) || user.isAdded(w)
    }

    // ---------- the person's own words ----------

    /** Adds a word to the person's dictionary. False if it isn't a plain word or is already known. */
    fun addWord(word: String): Boolean {
        val w = normalize(word.trim())
        if (!isLearnable(w) || isKnown(w)) return false
        return user.addWord(w)
    }

    /** Removes a word the person added. Built-in words can't be removed. */
    fun removeWord(word: String): Boolean = user.removeWord(normalize(word.trim()))

    fun addedWords(): List<String> = user.addedWordList()

    fun clearAddedWords() = user.clearAddedWords()

    /** Re-reads the added words from storage, e.g. after restoring a backup. */
    fun reloadUserWords() = user.reloadAddedWords()

    // ---------- probability ----------

    private class PredictionContext(
        val curated: Map<String, Double>,
        /** Corpus counts of what followed the last word, and their total. */
        val pairRow: Map<String, Float>?,
        val pairTotal: Double,
        /** Corpus counts of what followed the last two words, and their total. */
        val tripleRow: Map<String, Float>?,
        val tripleTotal: Double,
        val userRow: Map<String, Float>?,
        val userRowTotal: Double,
        val beta: Double,
        /** Weight of the word-pair level over plain frequency. */
        val lambda2: Double,
        /** Weight of the word-triple level over everything below it. */
        val lambda3: Double,
        val alpha: Double,
        val userTotal: Double
    )

    private var cachedPrev: String? = null
    private var cachedVersion = -1
    private var cachedTables: NgramTables? = null
    private var cachedContext: PredictionContext? = null

    /** One keystroke asks for this several times with the same inputs, so remember the last answer. */
    private fun contextFor(previous: String): PredictionContext {
        val prev = normalize(previous)
        val cached = cachedContext
        val tables = ngrams
        if (cached != null && cachedPrev == prev && cachedVersion == user.dataVersion &&
            cachedTables === tables
        ) {
            return cached
        }
        val built = buildContext(prev)
        cachedPrev = prev
        cachedVersion = user.dataVersion
        cachedTables = tables
        cachedContext = built
        return built
    }

    /** Witten-Bell weight: how much to trust what followed a context in the corpus. */
    private fun contextWeight(info: FloatArray?): Double {
        if (info == null || info[0] <= 0f) return 0.0
        val seen = info[0].toDouble()
        return minOf(MAX_CONTEXT_WEIGHT, seen / (seen + info[1]))
    }

    private fun buildContext(previous: String): PredictionContext {
        val p1 = lastWord(previous)
        val p2 = wordBeforeLast(previous)
        val tables = ngrams

        val curatedRaw = bigrams[p1]
        val curated: Map<String, Double> = if (curatedRaw == null) {
            emptyMap()
        } else {
            val sum = curatedRaw.sumOf { it.second }
            curatedRaw.associate { it.first to it.second / sum }
        }

        val pairInfo = if (p1.isEmpty()) null else tables?.contexts?.get(p1)
        val pairRow = if (p1.isEmpty()) null else tables?.pairs?.get(p1)
        val tripleKey = "$p2 $p1"
        val tripleInfo = if (p2.isEmpty() || p1.isEmpty()) null else tables?.contexts?.get(tripleKey)
        val tripleRow = if (tripleInfo == null) null else tables?.triples?.get(tripleKey)

        val row = user.bigramRow(p1)
        val rowTotal = row?.values?.sum()?.toDouble() ?: 0.0
        val beta = if (rowTotal > 0.0) {
            minOf(MAX_USER_BIGRAM_WEIGHT, rowTotal / (rowTotal + BIGRAM_TRUST_SCALE))
        } else {
            0.0
        }

        val lambda2 = when {
            pairRow != null -> contextWeight(pairInfo)
            curated.isNotEmpty() || rowTotal > 0.0 -> BIGRAM_WEIGHT
            else -> 0.0
        }
        // The person's own habits after p1 shrink the triple level, so they aren't drowned out.
        val lambda3 = if (tripleRow != null) contextWeight(tripleInfo) * (1.0 - beta) else 0.0

        val total = user.unigramTotal().toDouble()
        val alpha = if (total > 0.0) minOf(MAX_USER_UNIGRAM_WEIGHT, total / (total + UNIGRAM_TRUST_SCALE)) else 0.0

        return PredictionContext(
            curated = curated,
            pairRow = pairRow,
            pairTotal = pairInfo?.get(0)?.toDouble() ?: 0.0,
            tripleRow = tripleRow,
            tripleTotal = tripleInfo?.get(0)?.toDouble() ?: 0.0,
            userRow = row,
            userRowTotal = rowTotal,
            beta = beta,
            lambda2 = lambda2,
            lambda3 = lambda3,
            alpha = alpha,
            userTotal = total
        )
    }

    /** How common [word] is in general, before anything learned from the person. */
    private fun priorFrequency(word: String): Double {
        val freq = staticFreq
        if (freq != null) return freq[word] ?: (if (user.isAdded(word)) addedWordFreq else 0.0)
        val r: Int = rank[word] ?: (if (user.isAdded(word)) ADDED_WORD_RANK else return 0.0)
        return 1.0 / ((r + 1) * harmonic)
    }

    private fun probability(word: String, ctx: PredictionContext): Double {
        val prior = priorFrequency(word)
        val userFreq = if (ctx.userTotal > 0.0) user.unigramCount(word) / ctx.userTotal else 0.0
        var p = (1.0 - ctx.alpha) * prior + ctx.alpha * userFreq

        if (ctx.lambda2 > 0.0) {
            val cur = ctx.curated[word] ?: 0.0
            val corpus = if (ctx.pairRow != null && ctx.pairTotal > 0.0) {
                (ctx.pairRow[word] ?: 0f) / ctx.pairTotal
            } else {
                0.0
            }
            val general = when {
                ctx.pairRow == null -> cur
                ctx.curated.isEmpty() -> corpus
                else -> (1.0 - CURATED_SHARE) * corpus + CURATED_SHARE * cur
            }
            val usr = if (ctx.userRow != null && ctx.userRowTotal > 0.0) {
                (ctx.userRow[word] ?: 0f) / ctx.userRowTotal
            } else {
                0.0
            }
            val pair = (1.0 - ctx.beta) * general + ctx.beta * usr
            p = ctx.lambda2 * pair + (1.0 - ctx.lambda2) * p
        }

        if (ctx.lambda3 > 0.0 && ctx.tripleRow != null && ctx.tripleTotal > 0.0) {
            val triple = (ctx.tripleRow[word] ?: 0f) / ctx.tripleTotal
            p = ctx.lambda3 * triple + (1.0 - ctx.lambda3) * p
        }
        return p + FLOOR
    }

    /** Overall frequency of a word (blended with the person's own usage). */
    fun frequencyOf(word: String): Double = probability(normalize(word), contextFor(""))

    /**
     * How plausible each word is after [previous], scaled to 0..1 on a log
     * curve. Used by the autocorrector to break ties toward likelier words.
     */
    fun priorScorer(previous: String): (String) -> Double {
        val ctx = contextFor(previous)
        return { word ->
            ((ln(probability(word, ctx)) - LN_FLOOR) / (LN_CEIL - LN_FLOOR)).coerceIn(0.0, 1.0)
        }
    }

    // ---------- prediction ----------

    /**
     * The [count] most likely words that start with [prefix] (including the
     * prefix itself if it is a word), best first.
     */
    fun suggestions(previousWord: String, prefix: String, count: Int): List<String> {
        val p = normalize(prefix)
        val idx = vocabIndex()
        val ctx = contextFor(previousWord)
        val scored = ArrayList<Pair<String, Double>>()
        var i = firstWithPrefix(idx, p)
        while (i < idx.sorted.size && idx.sorted[i].startsWith(p)) {
            val w = idx.sorted[i]
            scored.add(w to probability(w, ctx))
            i++
        }
        scored.sortByDescending { it.second }
        return scored.take(count).map { it.first }
    }

    private var firstLetterPrev: String? = null
    private var firstLetterData = -1
    private var firstLetterVocab = -1
    private var firstLetterTables: NgramTables? = null
    private var firstLetterCache: Map<Char, Double> = emptyMap()

    /**
     * How likely each next letter (a-z) is after [prefix], judged by adding up
     * the probability of every real word that could follow it, so "wh" leans
     * toward 'a' because what/wha... outweigh the rest. The shares add up to 1.
     * Empty when no word continues the prefix.
     */
    fun letterDistribution(previousWord: String, prefix: String): Map<Char, Double> {
        val p = normalize(prefix)
        val prev = normalize(previousWord)
        val tables = ngrams
        if (p.isEmpty() && firstLetterPrev == prev &&
            firstLetterData == user.dataVersion && firstLetterVocab == user.vocabularyVersion &&
            firstLetterTables === tables
        ) {
            return firstLetterCache
        }
        val idx = vocabIndex()
        val ctx = contextFor(previousWord)
        val mass = HashMap<Char, Double>()
        var total = 0.0
        var i = if (p.isEmpty()) 0 else firstWithPrefix(idx, p)
        while (i < idx.sorted.size) {
            val w = idx.sorted[i]
            if (!w.startsWith(p)) break
            i++
            if (w.length == p.length) continue
            val c = w[p.length]
            if (c < 'a' || c > 'z') continue
            val pr = probability(w, ctx)
            total += pr
            mass[c] = (mass[c] ?: 0.0) + pr
        }
        val result: Map<Char, Double> = if (total <= 0.0) emptyMap() else mass.mapValues { it.value / total }
        if (p.isEmpty()) {
            firstLetterPrev = prev
            firstLetterData = user.dataVersion
            firstLetterVocab = user.vocabularyVersion
            firstLetterTables = tables
            firstLetterCache = result
        }
        return result
    }

    /** The single likeliest next letter, or null when nothing is likely enough to be worth showing. */
    fun predictNextChar(previousWord: String, prefix: String): Char? {
        var bestChar: Char? = null
        var bestMass = 0.0
        for ((c, m) in letterDistribution(previousWord, prefix)) {
            if (m > bestMass) {
                bestMass = m
                bestChar = c
            }
        }
        if (bestChar == null || bestMass < MIN_CONFIDENCE) return null
        return bestChar
    }

    // ---------- learning ----------

    /**
     * Record that [word] was typed after [previous] (a context from
     * [pushContext], [START], or "" if the context is unknown). Words
     * that aren't real words are ignored.
     */
    fun learn(previous: String, word: String, weight: Float = 1f) {
        val w = normalize(word)
        if (!isLearnable(w) || !isKnown(w)) return
        user.learn(contextWord(previous), w, weight)
    }

    fun unlearn(previous: String, word: String, weight: Float = 1f) {
        val w = normalize(word)
        if (!isLearnable(w) || !isKnown(w)) return
        user.unlearn(contextWord(previous), w, weight)
    }

    /** The word learning is keyed on: the last word of the context. */
    private fun contextWord(previous: String): String {
        val p = lastWord(previous)
        return if (p == START || isKnown(p)) p else ""
    }

    /** Housekeeping and saving; the keyboard calls this during a pause in typing. */
    fun flush() = user.flush()

    /** Forget everything learned from the person's typing (added words are kept). */
    fun clearLearned(context: Context) = user.clear(context.applicationContext)

}
