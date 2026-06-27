package com.kakao.taxi.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.kakao.taxi.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class NowBriefRepository(
    private val context: Context,
    private val dataStore: DataStoreRepository
) {
    companion object {
        private const val TAG = "NowBriefRepository"
        // ── Obfuscated API keys (XOR encoded — not plaintext in APK) ───────
        // To regenerate: each byte XOR'd with repeating salt key
        // Salt: "NowBrief2024!" repeated
        private val SALT = "NowBrief2024!".toByteArray()
        private fun dec(enc: ByteArray): String {
            val out = ByteArray(enc.size)
            for (i in enc.indices) out[i] = (enc[i].toInt() xor SALT[i % SALT.size].toInt()).toByte()
            return String(out)
        }
        // AIzaSyA1AIu5DHr5qXtqfuydUn7Yj8QKvOQPh_8
        private val ENC_GEMINI = byteArrayOf(15,38,13,35,33,16,36,87,115,121,71,1,101,6,29,66,51,42,29,20,0,71,73,86,97,79,121,54,29,122,35,34,19,41,99,96,90,107,25)
        // c317a0306e8a44a3abde147739862e1c
        private val ENC_WORLDNEWS = byteArrayOf(45,92,70,117,19,89,86,86,4,85,10,85,21,122,14,68,35,16,13,0,87,6,7,5,7,24,118,89,69,39,67,10)
        // pub_17bb40c0b79c412084b85106fcdcbd9e
        private val ENC_NEWSDATA = byteArrayOf(62,26,21,29,67,94,7,4,6,0,81,4,67,121,86,20,118,67,91,85,94,6,82,10,1,16,126,89,17,33,22,10,7,2,11,85)

        private val GEMINI_API_KEY   by lazy { dec(ENC_GEMINI) }
        private val WORLD_NEWS_API_KEY by lazy { dec(ENC_WORLDNEWS) }
        private val NEWSDATA_API_KEY   by lazy { dec(ENC_NEWSDATA) }
        private val GEMINI_URL get() =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$GEMINI_API_KEY"

        // ── RSS feeds — zero quota, unlimited, fastest source ──────────────
        private val RSS_GLOBAL = listOf(
            "World"         to "https://feeds.bbci.co.uk/news/world/rss.xml",
            "World"         to "https://feeds.reuters.com/reuters/topNews",
            "Technology"    to "https://feeds.bbci.co.uk/news/technology/rss.xml",
            "Technology"    to "https://techcrunch.com/feed/",
            "Business"      to "https://feeds.bbci.co.uk/news/business/rss.xml",
            "Business"      to "https://feeds.reuters.com/reuters/businessNews",
            "Science"       to "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
            "Health"        to "https://feeds.bbci.co.uk/news/health/rss.xml",
            "Entertainment" to "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
            "Sports"        to "https://feeds.bbci.co.uk/sport/rss.xml"
        )

        // Google News localised RSS — zero cost, adapts to any country
        fun googleNewsRss(cc: String, lang: String) =
            "https://news.google.com/rss?hl=$lang-${cc.uppercase()}&gl=${cc.uppercase()}&ceid=${cc.uppercase()}:$lang"

        // Country-specific trusted local RSS
        private val RSS_LOCAL_BY_COUNTRY = mapOf(
            "in" to listOf("General" to "https://feeds.feedburner.com/ndtvnews-top-stories",
                           "General" to "https://timesofindia.indiatimes.com/rssfeedstopstories.cms"),
            "us" to listOf("General" to "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml",
                           "General" to "https://feeds.npr.org/1001/rss.xml"),
            "gb" to listOf("General" to "https://feeds.bbci.co.uk/news/uk/rss.xml",
                           "General" to "https://www.theguardian.com/uk/rss"),
            "au" to listOf("General" to "https://www.abc.net.au/news/feed/51120/rss.xml"),
            "ca" to listOf("General" to "https://www.cbc.ca/cmlink/rss-topstories"),
            "de" to listOf("General" to "https://www.spiegel.de/schlagzeilen/tops/index.rss"),
            "fr" to listOf("General" to "https://www.lemonde.fr/rss/une.xml"),
            "sg" to listOf("General" to "https://www.straitstimes.com/news/singapore/rss.xml"),
            "pk" to listOf("General" to "https://www.dawn.com/feeds/home"),
            "ng" to listOf("General" to "https://guardian.ng/feed/"),
            "za" to listOf("General" to "https://www.news24.com/rss"),
            "jp" to listOf("General" to "https://www3.nhk.or.jp/rss/news/cat0.xml"),
            "kr" to listOf("General" to "https://koreajoongangdaily.joins.com/section/rss/all.xml"),
            "br" to listOf("General" to "https://g1.globo.com/rss/g1/")
        )
        // newsdata.io requires short codes: "ko", "ja", "de" etc — NOT full names
        private val COUNTRY_LANGUAGE_MAP = mapOf(
            "kr" to "ko", "jp" to "ja", "cn" to "zh",
            "de" to "de", "fr" to "fr", "es" to "es",
            "pt" to "pt", "it" to "it", "ru" to "ru",
            "ar" to "ar", "sa" to "ar", "eg" to "ar",
            "tr" to "tr", "nl" to "nl", "pl" to "pl",
            "se" to "sv", "no" to "no", "dk" to "da",
            "th" to "th", "vn" to "vi", "id" to "id",
            "bd" to "bn", "pk" to "ur", "np" to "ne"
        )
        fun languageForCountry(countryCode: String): String =
            COUNTRY_LANGUAGE_MAP[countryCode] ?: "en"

        // Map from country name → newsdata.io country code
        private val COUNTRY_CODE_MAP = mapOf(
            "india" to "in", "united states" to "us", "us" to "us", "usa" to "us",
            "united kingdom" to "gb", "uk" to "gb", "australia" to "au",
            "canada" to "ca", "germany" to "de", "france" to "fr", "japan" to "jp",
            "china" to "cn", "brazil" to "br", "russia" to "ru", "italy" to "it",
            "spain" to "es", "mexico" to "mx", "south korea" to "kr", "korea" to "kr", "indonesia" to "id",
            "netherlands" to "nl", "sweden" to "se", "norway" to "no", "denmark" to "dk",
            "singapore" to "sg", "pakistan" to "pk", "bangladesh" to "bd",
            "sri lanka" to "lk", "nepal" to "np", "thailand" to "th",
            "malaysia" to "my", "philippines" to "ph", "vietnam" to "vn",
            "south africa" to "za", "nigeria" to "ng", "kenya" to "ke",
            "egypt" to "eg", "turkey" to "tr", "saudi arabia" to "sa",
            "uae" to "ae", "united arab emirates" to "ae", "israel" to "il",
            "argentina" to "ar", "colombia" to "co", "chile" to "cl"
        )

        /** Returns a newsdata.io country code.
         *  Falls back to device locale country (e.g. KR in Korea), then "us". */
        fun countryCodeForName(countryName: String): String =
            COUNTRY_CODE_MAP[countryName.lowercase().trim()]
                ?: Locale.getDefault().country.lowercase().takeIf { it.isNotBlank() }
                ?: "us"
    }

    private val _briefState = MutableStateFlow(BriefState(isLoading = false))
    val briefState: StateFlow<BriefState> = _briefState.asStateFlow()

    private var cachedLocation: Triple<Double, Double, String>? = null

    // ── News cache: keyed by country code, expires after 30 min ─────────
    // Prevents hammering World News API rate limits when many users refresh
    private data class NewsCacheEntry(val articles: List<NewsArticle>, val fetchedAt: Long, val countryCode: String)
    private var localNewsCache: NewsCacheEntry? = null
    private var worldNewsCache: NewsCacheEntry? = null
    private val NEWS_CACHE_TTL_MS = 30 * 60 * 1000L  // 30 minutes

    private fun localNewsCacheValid(): Boolean {
        val c = localNewsCache ?: return false
        return c.countryCode == cachedCountryCode &&
               (System.currentTimeMillis() - c.fetchedAt) < NEWS_CACHE_TTL_MS
    }
    private fun worldNewsCacheValid(): Boolean {
        val c = worldNewsCache ?: return false
        return (System.currentTimeMillis() - c.fetchedAt) < NEWS_CACHE_TTL_MS
    }
    private var quoteIndex = (0..99).random()
    private val quotePool = listOf(
        // Motivation & success
        "The best way to predict the future is to create it. — Abraham Lincoln",
        "It always seems impossible until it's done. — Nelson Mandela",
        "Do what you can, with what you have, where you are. — Theodore Roosevelt",
        "In the middle of difficulty lies opportunity. — Albert Einstein",
        "The journey of a thousand miles begins with one step. — Lao Tzu",
        "That which does not kill us makes us stronger. — Friedrich Nietzsche",
        "You miss 100% of the shots you don't take. — Wayne Gretzky",
        "Whether you think you can or you think you can't, you're right. — Henry Ford",
        "The only way to do great work is to love what you do. — Steve Jobs",
        "Simplicity is the ultimate sophistication. — Leonardo da Vinci",
        "Stay hungry, stay foolish. — Steve Jobs",
        "Be yourself; everyone else is already taken. — Oscar Wilde",
        "I have not failed. I've just found 10,000 ways that won't work. — Thomas Edison",
        "You only live once, but if you do it right, once is enough. — Mae West",
        "Live as if you were to die tomorrow. Learn as if you were to live forever. — Mahatma Gandhi",
        "Without music, life would be a mistake. — Friedrich Nietzsche",
        "The secret of getting ahead is getting started. — Mark Twain",
        "It does not matter how slowly you go as long as you do not stop. — Confucius",
        "Our greatest glory is not in never falling, but in rising every time we fall. — Confucius",
        "Everything you've ever wanted is on the other side of fear. — George Addair",
        "Dream big and dare to fail. — Norman Vaughan",
        "You must be the change you wish to see in the world. — Mahatma Gandhi",
        "When we strive to become better than we are, everything around us becomes better too. — Paulo Coelho",
        "Happiness is not something ready made. It comes from your own actions. — Dalai Lama",
        "It is during our darkest moments that we must focus to see the light. — Aristotle",
        "Try to be a rainbow in someone's cloud. — Maya Angelou",
        "Do not go where the path may lead; go instead where there is no path and leave a trail. — Ralph Waldo Emerson",
        "Not how long, but how well you have lived is the main thing. — Seneca",
        "Your time is limited, so don't waste it living someone else's life. — Steve Jobs",
        "Get busy living or get busy dying. — Stephen King",
        // Wisdom & philosophy
        "Knowing yourself is the beginning of all wisdom. — Aristotle",
        "The unexamined life is not worth living. — Socrates",
        "We are what we repeatedly do. Excellence is not an act, but a habit. — Aristotle",
        "Count your age by friends, not years. Count your life by smiles, not tears. — John Lennon",
        "In three words I can sum up everything I've learned about life: it goes on. — Robert Frost",
        "No act of kindness, no matter how small, is ever wasted. — Aesop",
        "To live is the rarest thing in the world. Most people exist, that is all. — Oscar Wilde",
        "Yesterday is history, tomorrow is a mystery, today is a gift — that's why it's called the present. — Eleanor Roosevelt",
        "A friend is someone who knows all about you and still loves you. — Elbert Hubbard",
        "To handle yourself, use your head; to handle others, use your heart. — Eleanor Roosevelt",
        // Perseverance
        "Fall seven times, stand up eight. — Japanese Proverb",
        "Hard times never last, but hard people do. — Robert H. Schuller",
        "Tough times never last, but tough people do. — Dr. Robert Schuller",
        "The gem cannot be polished without friction, nor man perfected without trials. — Chinese Proverb",
        "Character cannot be developed in ease and quiet. — Helen Keller",
        "You are never too old to set another goal or to dream a new dream. — C.S. Lewis",
        "The difference between ordinary and extraordinary is that little extra. — Jimmy Johnson",
        "Don't watch the clock; do what it does. Keep going. — Sam Levenson",
        "Energy and persistence conquer all things. — Benjamin Franklin",
        "Perseverance is not a long race; it is many short races one after the other. — Walter Elliot",
        // Creativity & learning
        "Creativity is intelligence having fun. — Albert Einstein",
        "An investment in knowledge pays the best interest. — Benjamin Franklin",
        "Education is the most powerful weapon which you can use to change the world. — Nelson Mandela",
        "The more that you read, the more things you will know. — Dr. Seuss",
        "Logic will get you from A to B. Imagination will take you everywhere. — Albert Einstein",
        "The greatest discovery of all time is that a person can change his future by merely changing his attitude. — Oprah Winfrey",
        "Once you stop learning, you start dying. — Albert Einstein",
        "The beautiful thing about learning is that no one can take it away from you. — B.B. King",
        // Courage & action
        "Courage is not the absence of fear, but the triumph over it. — Nelson Mandela",
        "It takes courage to grow up and become who you really are. — E.E. Cummings",
        "The most courageous act is still to think for yourself. Aloud. — Coco Chanel",
        "Life shrinks or expands in proportion to one's courage. — Anais Nin",
        "Twenty years from now you will be more disappointed by the things you didn't do than by the ones you did. — Mark Twain",
        "A ship in harbor is safe, but that is not what ships are built for. — John A. Shedd",
        "Do one thing every day that scares you. — Eleanor Roosevelt",
        "You don't have to be great to start, but you have to start to be great. — Zig Ziglar",
        // Happiness & peace
        "Happiness depends upon ourselves. — Aristotle",
        "The purpose of our lives is to be happy. — Dalai Lama",
        "Very little is needed to make a happy life; it is all within yourself. — Marcus Aurelius",
        "Whoever is happy will make others happy too. — Anne Frank",
        "Happiness is not a destination, it is a way of life. — Burton Hills",
        "The happiest people don't have the best of everything, they make the best of everything. — Anonymous",
        "Joy is not in things; it is in us. — Richard Wagner",
        "Be happy for this moment. This moment is your life. — Omar Khayyam",
        // Indian wisdom
        "Strength does not come from physical capacity. It comes from an indomitable will. — Mahatma Gandhi",
        "You have power over your mind — not outside events. Realize this, and you will find strength. — Marcus Aurelius",
        "First they ignore you, then they laugh at you, then they fight you, then you win. — Mahatma Gandhi",
        "A man is but the product of his thoughts. What he thinks, he becomes. — Mahatma Gandhi",
        "The best way to find yourself is to lose yourself in the service of others. — Mahatma Gandhi",
        "Arise, awake, and stop not until the goal is achieved. — Swami Vivekananda",
        "Take up one idea. Make that one idea your life. — Swami Vivekananda",
        "Talk to yourself once in a day, otherwise you may miss meeting an excellent person. — Swami Vivekananda",
        "In a gentle way, you can shake the world. — Mahatma Gandhi",
        "The future depends on what you do today. — Mahatma Gandhi",
        // Tech & modern
        "Move fast and break things. — Mark Zuckerberg",
        "Make something people want. — Paul Graham",
        "The best way to not feel hopeless is to get up and do something. — Barack Obama",
        "We cannot solve our problems with the same thinking we used when we created them. — Albert Einstein",
        "Innovation distinguishes between a leader and a follower. — Steve Jobs",
        "The people who are crazy enough to think they can change the world are the ones who do. — Steve Jobs",
        "Technology is best when it brings people together. — Matt Mullenweg",
        "Any sufficiently advanced technology is indistinguishable from magic. — Arthur C. Clarke",
        // Short punchy
        "Just do it. — Nike",
        "Think different. — Apple",
        "Less is more. — Ludwig Mies van der Rohe",
        "Progress, not perfection. — Anonymous",
        "Done is better than perfect. — Sheryl Sandberg",
        "Work hard, be kind, and amazing things will happen. — Conan O'Brien",
        "Act as if what you do makes a difference. It does. — William James",
        "Nothing is impossible. The word itself says 'I'm possible.' — Audrey Hepburn",
        "If not us, who? If not now, when? — John F. Kennedy",
        "Be the change. — Mahatma Gandhi"
    ).shuffled().toMutableList()

    fun getNextQuote(): String = quotePool[quoteIndex++ % quotePool.size]

    /** Call this after location permission is granted so the next fetch gets real coordinates. */
    fun clearLocationCache() {
        cachedLocation = null
    }

    fun getContextualGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetings = when {
            hour < 5  -> listOf("Still up? Rest well 🌙", "Burning the midnight oil 🌙", "Late night, take care 🌙")
            hour < 12 -> listOf("Good morning", "Rise and shine ☀️", "Morning! Let's make it great", "Good morning! Ready for today?")
            hour < 17 -> listOf("Good afternoon", "Hope your day's going well", "Afternoon! Keep the momentum 🌤️")
            hour < 21 -> listOf("Good evening", "Evening! Time to unwind 🌅", "Good evening! How was your day?")
            else      -> listOf("Time to relax 🌙", "Good night! Rest up 🌙", "Winding down for the night 🌙")
        }
        return greetings.random()
    }

    suspend fun refreshAll() {
        _briefState.value = _briefState.value.copy(isLoading = true, error = null)
        try {
            val weather = fetchWeather()
            val news    = fetchNews()
            val summary = fetchGeminiSummary(weather, news)
            val focus   = fetchDailyFocus(weather)
            _briefState.value = BriefState(
                summary     = summary.copy(focus = focus),
                weather     = weather,
                news        = news,
                isLoading   = false,
                lastUpdated = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "refreshAll error", e)
            _briefState.value = _briefState.value.copy(
                isLoading = false,
                error     = e.message ?: "Failed to load data"
            )
        }
    }

    /** One powerful AI-generated focus goal for the day */
    private suspend fun fetchDailyFocus(weather: WeatherData): com.kakao.taxi.data.model.DailyFocus =
        withContext(Dispatchers.IO) {
            try {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeOfDay = when { hour < 12 -> "morning"; hour < 17 -> "afternoon"; else -> "evening" }
                val prompt = """Return ONLY valid JSON, no markdown, no explanation:
{"goal":"One clear focus goal for the $timeOfDay (max 8 words)","steps":"Two micro-actions separated by a dot (max 10 words each)"}
Context: ${weather.condition} ${weather.temperature.toInt()}C in ${weather.cityName}, UV ${weather.uvIndex}, Air quality: ${weather.airQualityLabel.ifBlank { "Good" }}.""".trimIndent()
                val raw = callGemini(buildGeminiRequest(prompt, 0.8, 200))
                val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
                if (s < 0 || e < 0) return@withContext com.kakao.taxi.data.model.DailyFocus()
                val j = JSONObject(raw.substring(s, e + 1))
                com.kakao.taxi.data.model.DailyFocus(
                    goal  = j.optString("goal", "").trim(),
                    steps = j.optString("steps", "").trim()
                )
            } catch (e: Exception) {
                Log.e(TAG, "fetchDailyFocus: ${e.message}")
                com.kakao.taxi.data.model.DailyFocus()
            }
        }

    /** Fetches a fresh quote + music from Gemini and updates state in-place, without reloading weather/news. */
    suspend fun refreshQuoteAndMusic() = withContext(Dispatchers.IO) {
        try {
            val current = _briefState.value
            val weather = current.weather
            val news    = current.news
            if (weather.cityName == "Unknown" && weather.temperature == 0.0) return@withContext

            val hour      = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when { hour < 12 -> "morning"; hour < 17 -> "afternoon"; hour < 21 -> "evening"; else -> "night" }
            val today     = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())

            val prompt = """
You are NowBrief. Generate a fresh inspiring quote and music recommendation.
Today: $today ($timeOfDay), Weather: ${weather.condition} ${weather.temperature.toInt()}°C in ${weather.cityName}.
Respond ONLY with valid JSON (no markdown):
{
  "quote": "Inspiring quote with attribution",
  "musicTitle": "A real song title different from last time",
  "musicArtist": "Artist name",
  "musicMoodTags": ["mood1", "mood2"],
  "musicReason": "Max 8 words why"
}
""".trimIndent()

            val text = callGemini(buildGeminiRequest(prompt, 1.0, 400))
            val data = JSONObject(text)

            val moodTagsArr = data.optJSONArray("musicMoodTags")
            val moodTags = if (moodTagsArr != null && moodTagsArr.length() >= 2)
                listOf(moodTagsArr.getString(0), moodTagsArr.getString(1))
            else listOf("Uplifting", "Fresh")

            val newSummary = current.summary.copy(
                // Always advance to next quote — use Gemini's if returned, else local pool
                quote = data.optString("quote", "").ifBlank { getNextQuote() },
                music = MusicRecommendation(
                    title  = data.optString("musicTitle", current.summary.music?.title ?: "Lovely Day"),
                    artist = data.optString("musicArtist", current.summary.music?.artist ?: "Bill Withers"),
                    mood   = moodTags.joinToString(" · "),
                    reason = data.optString("musicReason", "Perfect for now")
                )
            )
            _briefState.value = current.copy(summary = newSummary)
        } catch (e: Exception) {
            Log.e(TAG, "refreshQuoteAndMusic error", e)
        }
    }

    // ── LOCATION ────────────────────────────────────────────────────

    private suspend fun getLocation(): Triple<Double, Double, String> {
        // 1. Return in-memory cache if available (within same session)
        cachedLocation?.let { return it }

        return withContext(Dispatchers.IO) {
            // 2. Try to get a fresh location from device (with 8s timeout)
            val fresh = tryGetFreshLocation()
            if (fresh != null) {
                val city = reverseGeocode(fresh.first, fresh.second)
                val result = Triple(fresh.first, fresh.second, city)
                cachedLocation = result
                try { dataStore.saveLocation(fresh.first, fresh.second, city) } catch (_: Exception) {}
                return@withContext result
            }

            // 3. Fall back to last persisted location from DataStore
            val persisted = try { dataStore.loadLocation() } catch (_: Exception) { null }
            if (persisted != null) {
                cachedLocation = persisted
                return@withContext persisted
            }

            // 4. Ultimate fallback — do NOT cache this so next call retries
            Triple(22.5958, 88.2636, "Howrah")
        }
    }

    /**
     * Tries to get a fresh device location.
     * Uses a dedicated HandlerThread so we never block or deadlock the main thread.
     * Strategy:
     *   1. getLastKnownLocation (instant) — use if < 30 min old
     *   2. requestLocationUpdates with 10s timeout on a background HandlerThread
     *   3. Return null if both fail (caller will use DataStore fallback)
     */
    private suspend fun tryGetFreshLocation(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            return null
        }

        return withContext(Dispatchers.IO) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Step 1: check last known from all enabled providers
            val enabledProviders = try { lm.getProviders(true) } catch (_: Exception) { emptyList() }
            var best: android.location.Location? = null
            for (provider in enabledProviders) {
                val loc = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
                if (best == null || loc.accuracy < best.accuracy) best = loc
            }
            if (best != null && (System.currentTimeMillis() - best.time) < 30 * 60 * 1000L) {
                Log.d(TAG, "Using recent last-known location: ${best.latitude}, ${best.longitude}")
                return@withContext Pair(best.latitude, best.longitude)
            }

            // Step 2: request a fresh update on a dedicated HandlerThread (avoids main-thread deadlock)
            val freshLoc = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    // Own looper so we never touch the main thread
                    val handlerThread = android.os.HandlerThread("LocationFix").also { it.start() }
                    val handler = android.os.Handler(handlerThread.looper)

                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: android.location.Location) {
                            lm.removeUpdates(this)
                            handlerThread.quit()
                            Log.d(TAG, "Fresh location fix: ${loc.latitude}, ${loc.longitude} acc=${loc.accuracy}m")
                            if (cont.isActive) cont.resume(loc)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }

                    cont.invokeOnCancellation {
                        try { lm.removeUpdates(listener) } catch (_: Exception) {}
                        handlerThread.quit()
                    }

                    // Prefer NETWORK (fast) then GPS (accurate but slow)
                    val provider = when {
                        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)     -> LocationManager.GPS_PROVIDER
                        else -> null
                    }

                    if (provider != null) {
                        try {
                            lm.requestLocationUpdates(provider, 0L, 0f, listener, handlerThread.looper)
                            Log.d(TAG, "Requested location updates via $provider")
                        } catch (e: Exception) {
                            Log.e(TAG, "requestLocationUpdates failed: ${e.message}")
                            handlerThread.quit()
                            cont.resume(null)
                        }
                    } else {
                        Log.w(TAG, "No location provider enabled")
                        handlerThread.quit()
                        cont.resume(null)
                    }
                }
            }

            val result = freshLoc ?: best
            if (result != null) {
                Log.d(TAG, "Location resolved: ${result.latitude}, ${result.longitude}")
                Pair(result.latitude, result.longitude)
            } else {
                Log.w(TAG, "Could not resolve fresh location")
                null
            }
        }
    }

    // Default to device locale country until GPS resolves (e.g. "kr" in Korea, "us" in US)
    private var cachedCountryCode: String = Locale.getDefault().country.lowercase().takeIf { it.isNotBlank() } ?: "us"

    private fun reverseGeocode(lat: Double, lon: Double): String {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=10&accept-language=en"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NowBriefApp/2.0 (contact@nowbrief.app)")
            conn.setRequestProperty("Accept-Language", "en")
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
                       else { Log.w(TAG, "Nominatim HTTP $code"); return "Your City" }
            val json = JSONObject(body)
            val addr = json.optJSONObject("address")

            // Extract country code directly (ISO 3166-1 alpha-2) — more reliable than name
            val countryCode = addr?.optString("country_code", "")?.lowercase() ?: ""
            if (countryCode.isNotBlank()) {
                cachedCountryCode = countryCode
                Log.d(TAG, "Geocoded: country_code='$countryCode'")
            } else {
                val country = addr?.optString("country") ?: ""
                if (country.isNotBlank()) cachedCountryCode = countryCodeForName(country)
                Log.d(TAG, "Geocoded: country='$country' code='$cachedCountryCode'")
            }

            // English city name — try progressively broader fields
            addr?.optString("city")?.takeIf       { it.isNotBlank() }
                ?: addr?.optString("town")?.takeIf    { it.isNotBlank() }
                ?: addr?.optString("municipality")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("village")?.takeIf  { it.isNotBlank() }
                ?: addr?.optString("suburb")?.takeIf   { it.isNotBlank() }
                ?: addr?.optString("district")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("county")?.takeIf   { it.isNotBlank() }
                ?: addr?.optString("state")?.takeIf    { it.isNotBlank() }
                ?: json.optString("display_name").split(",").firstOrNull()?.trim()
                ?: "Your City"
        } catch (e: Exception) {
            Log.e(TAG, "reverseGeocode error", e)
            "Your City"
        }
    }

    // ── WEATHER ─────────────────────────────────────────────────────

    private suspend fun fetchWeather(): WeatherData = withContext(Dispatchers.IO) {
        try {
            val (lat, lon, city) = getLocation()
            val url = "https://api.open-meteo.com/v1/forecast?" +
                      "latitude=$lat&longitude=$lon" +
                      "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                      "weather_code,wind_speed_10m,precipitation_probability,uv_index,visibility" +
                      "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
            val json    = JSONObject(httpGet(url))
            val current = json.getJSONObject("current")
            val daily   = json.getJSONObject("daily")
            val (condition, icon) = wmoToCondition(current.getInt("weather_code"))

            // Fetch Air Quality Index from Open-Meteo AQI endpoint (free, no key needed)
            var aqiValue = 0
            var aqiLabel = ""
            var aqiEmoji = "🟢"
            try {
                val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality?" +
                    "latitude=$lat&longitude=$lon&current=european_aqi"
                val aqiJson = JSONObject(httpGet(aqiUrl))
                aqiValue = aqiJson.optJSONObject("current")?.optInt("european_aqi", 0) ?: 0
                val (label, emoji) = when {
                    aqiValue <= 20  -> "Good"       to "🟢"
                    aqiValue <= 40  -> "Fair"       to "🟡"
                    aqiValue <= 60  -> "Moderate"   to "🟠"
                    aqiValue <= 80  -> "Poor"       to "🔴"
                    aqiValue <= 100 -> "Very Poor"  to "🟣"
                    else            -> "Hazardous"  to "⚫"
                }
                aqiLabel = label; aqiEmoji = emoji
            } catch (e: Exception) {
                Log.w(TAG, "AQI fetch failed (non-critical): ${e.message}")
            }

            WeatherData(
                condition         = condition,
                temperature       = current.getDouble("temperature_2m"),
                feelsLike         = current.getDouble("apparent_temperature"),
                humidity          = current.getInt("relative_humidity_2m"),
                windSpeed         = current.getDouble("wind_speed_10m"),
                cityName          = city,
                icon              = icon,
                uvIndex           = current.optInt("uv_index", 0),
                visibility        = current.optDouble("visibility", 10000.0) / 1000,
                rainChance        = current.optInt("precipitation_probability", 0),
                sunrise           = formatTime(daily.getJSONArray("sunrise").getString(0)),
                sunset            = formatTime(daily.getJSONArray("sunset").getString(0)),
                airQualityIndex   = aqiValue,
                airQualityLabel   = aqiLabel,
                airQualityEmoji   = aqiEmoji
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchWeather error", e)
            WeatherData(condition = "Clear", temperature = 28.0, cityName = "Your City", icon = "☀️")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RSS PARSER — no external library, pure XML string parsing
    // ══════════════════════════════════════════════════════════════════

    private data class RssItem(
        val title: String,
        val description: String,
        val link: String,
        val pubDate: String,
        val source: String,
        val category: String
    )

    /** Fetch one RSS URL and return parsed items. Tolerates malformed XML. */
    private fun fetchRss(url: String, defaultCategory: String, sourceName: String): List<RssItem> {
        return try {
            val xml  = httpGetSafe(url, mapOf("User-Agent" to "NowBriefApp/2.0 RSS reader"))
            parseRssXml(xml, defaultCategory, sourceName)
        } catch (e: Exception) {
            Log.w(TAG, "RSS fetch failed $url: ${e.message}")
            emptyList()
        }
    }

    /** Regex-based RSS/Atom XML parser — works without Android XML parser quirks */
    private fun parseRssXml(xml: String, defaultCategory: String, sourceName: String): List<RssItem> {
        val items = mutableListOf<RssItem>()
        // Match <item>...</item> or <entry>...</entry> (Atom)
        val itemPattern = Regex("""<(?:item|entry)[^>]*>(.*?)</(?:item|entry)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        fun tag(block: String, name: String): String {
            val pattern = Regex("""<$name[^>]*>(?:<!\[CDATA\[)?(.*?)(?:]]>)?</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            return pattern.find(block)?.groupValues?.get(1)?.trim()
                ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
                ?.replace("&quot;", "\"")?.replace("&#39;", "'")
                ?.replace(Regex("<[^>]+>"), "")  // strip inner tags
                ?.trim() ?: ""
        }
        fun attrOrTag(block: String, tag: String, attr: String): String {
            val attrVal = Regex("""<$tag[^>]*$attr=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1) ?: ""
            return attrVal.ifBlank { tag(block, tag) }
        }

        for (match in itemPattern.findAll(xml)) {
            val block = match.groupValues[1]
            val title = tag(block, "title").take(200).ifBlank { continue }
            val link  = attrOrTag(block, "link", "href").ifBlank { tag(block, "guid") }
            val desc  = tag(block, "description").ifBlank { tag(block, "summary") }.ifBlank { tag(block, "content") }.take(300)
            val date  = tag(block, "pubDate").ifBlank { tag(block, "published") }.ifBlank { tag(block, "updated") }
            val cat   = tag(block, "category").ifBlank { defaultCategory }
            val src   = sourceName.ifBlank {
                try { java.net.URL(link).host.removePrefix("www.") } catch (_: Exception) { "rss" }
            }
            items.add(RssItem(title, desc, link, date, src, cat.replaceFirstChar { it.uppercaseChar() }))
            if (items.size >= 8) break
        }
        return items
    }

    private fun RssItem.toArticle() = NewsArticle(
        title       = title,
        description = description,
        source      = source,
        url         = link,
        publishedAt = formatNewsDate(pubDate),
        category    = category
    )

    // ── NEWS: RSS primary → World News API supplement → Gemini fallback ──

    private suspend fun fetchNews(): List<NewsArticle> = withContext(Dispatchers.IO) {
        val cc   = cachedCountryCode.lowercase()
        val lang = languageForCountry(cc)

        // ── 1. RSS (parallel, no quota at all) ─────────────────────────
        val rssArticles = coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<RssItem>>>()

            // Google News localised RSS — best local coverage for any country
            jobs.add(async {
                fetchRss(googleNewsRss(cc, lang), "General",
                    "Google News ${cc.uppercase()}")
            })

            // Country-specific RSS feeds
            RSS_LOCAL_BY_COUNTRY[cc]?.forEach { (cat, url) ->
                jobs.add(async { fetchRss(url, cat, "") })
            }

            // Global feeds (run 4 in parallel to keep it fast)
            RSS_GLOBAL.take(6).forEach { (cat, url) ->
                jobs.add(async { fetchRss(url, cat, "") })
            }

            jobs.flatMap { it.await() }
        }

        val rssResult = rssArticles
            .distinctBy { it.title.take(55) }
            .sortedByDescending { parseRssDateMs(it.pubDate) }
            .map { it.toArticle() }

        Log.d(TAG, "RSS: ${rssResult.size} articles from ${rssArticles.size} raw items")

        // ── 2. World News API supplement (only if cache is warm — free call) ─
        val worldNewsSupp = if (worldNewsCacheValid()) {
            worldNewsCache!!.articles
        } else if (rssResult.size < 6) {
            // Only hit World News API if RSS returned too little
            try { fetchWorldNewsGlobal() } catch (_: Exception) { emptyList() }
        } else emptyList()

        // ── 3. Local World News API (cache-first) ──────────────────────
        val localApiSupp = if (localNewsCacheValid()) {
            localNewsCache!!.articles
        } else if (rssResult.count { it.category == "General" } < 3) {
            try { fetchWorldNewsLocal() } catch (_: Exception) { emptyList() }
        } else emptyList()

        val combined = (rssResult + worldNewsSupp + localApiSupp)
            .distinctBy { it.title.take(55) }
            .take(30)

        Log.d(TAG, "fetchNews: ${combined.size} total (${rssResult.size} RSS + ${worldNewsSupp.size} WNA-world + ${localApiSupp.size} WNA-local)")

        if (combined.size >= 3) return@withContext combined

        // ── 4. Last resort: newsdata.io → Gemini ──────────────────────
        Log.d(TAG, "All RSS failed, falling back to newsdata.io")
        try {
            val localLang = languageForCountry(cc)
            val langParam = if (localLang == "en") "en" else "$localLang,en"
            val newsUrl = "https://newsdata.io/api/1/latest?apikey=$NEWSDATA_API_KEY&language=$langParam&size=10&country=$cc"
            val json    = JSONObject(httpGet(newsUrl))
            if (json.optString("status") == "error") return@withContext fetchGeminiNews()
            val results = json.optJSONArray("results") ?: JSONArray()
            val list    = mutableListOf<NewsArticle>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val rawCat = item.optJSONArray("category")
                val cat = if (rawCat != null && rawCat.length() > 0)
                    rawCat.getString(0).replaceFirstChar { it.uppercaseChar() } else "General"
                list.add(NewsArticle(
                    title       = item.optString("title", "No title"),
                    description = item.optString("description", "").takeIf { it.isNotBlank() }
                                  ?: item.optString("content", "").take(200),
                    source      = item.optString("source_id", "newsdata.io").replaceFirstChar { it.uppercaseChar() },
                    url         = item.optString("link", ""),
                    publishedAt = formatNewsDate(item.optString("pubDate", "")),
                    category    = cat,
                    imageUrl    = item.optString("image_url", "").takeIf { it.isNotBlank() }
                ))
            }
            if (list.isNotEmpty()) list else fetchGeminiNews()
        } catch (e: Exception) {
            Log.e(TAG, "newsdata.io error: ${e.message}")
            fetchGeminiNews()
        }
    }

    /** Parse RSS pubDate or ISO date to epoch ms for sorting */
    private fun parseRssDateMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            listOf(
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd HH:mm:ss"
            ).firstNotNullOfOrNull { fmt ->
                try { SimpleDateFormat(fmt, Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)?.time }
                catch (_: Exception) { null }
            } ?: 0L
        } catch (_: Exception) { 0L }
    }


    /** Local/national top news for the user's country via World News API */
    private suspend fun fetchWorldNewsLocal(): List<NewsArticle> = withContext(Dispatchers.IO) {
        // Return cached result if fresh and same country
        if (localNewsCacheValid()) {
            Log.d(TAG, "fetchWorldNewsLocal: cache hit (${localNewsCache!!.articles.size} articles)")
            return@withContext localNewsCache!!.articles
        }

        val cc   = cachedCountryCode.lowercase()
        val lang = languageForCountry(cc)
        val articles = mutableListOf<NewsArticle>()

        // 1. Try top-news endpoint
        try {
            val url = "https://api.worldnewsapi.com/top-news" +
                "?api-key=$WORLD_NEWS_API_KEY&source-country=$cc&language=$lang"
            Log.d(TAG, "fetchWorldNewsLocal top-news: $url")
            val json    = JSONObject(httpGetSafe(url, mapOf("x-api-key" to WORLD_NEWS_API_KEY)))
            val topNews = json.optJSONArray("top_news")
            if (topNews != null) {
                for (i in 0 until topNews.length()) {
                    val newsArr = topNews.getJSONObject(i).optJSONArray("news") ?: continue
                    for (j in 0 until newsArr.length()) {
                        val n = newsArr.getJSONObject(j)
                        articles.addIfValid(n, cc, preserveCategory = true)
                        if (articles.size >= 12) break
                    }
                    if (articles.size >= 12) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "top-news failed: ${e.message}")
        }

        // 2. Supplement with search-news if needed
        if (articles.size < 5) {
            try {
                val url = "https://api.worldnewsapi.com/search-news" +
                    "?api-key=$WORLD_NEWS_API_KEY" +
                    "&source-country=$cc&language=$lang" +
                    "&number=10&sort=publish-time&sort-direction=DESC"
                Log.d(TAG, "fetchWorldNewsLocal search-news: $url")
                val json    = JSONObject(httpGetSafe(url, mapOf("x-api-key" to WORLD_NEWS_API_KEY)))
                val newsArr = json.optJSONArray("news")
                if (newsArr != null) {
                    for (i in 0 until newsArr.length()) {
                        articles.addIfValid(newsArr.getJSONObject(i), cc, preserveCategory = true)
                        if (articles.size >= 12) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "search-news local failed: ${e.message}")
            }
        }

        Log.d(TAG, "fetchWorldNewsLocal: ${articles.size} articles")
        val result = articles.distinctBy { it.title.take(60) }.take(12)
        if (result.isNotEmpty()) {
            localNewsCache = NewsCacheEntry(result, System.currentTimeMillis(), cc)
        }
        result
    }

    /** Global/international top stories via World News API — always in English */
    private suspend fun fetchWorldNewsGlobal(): List<NewsArticle> = withContext(Dispatchers.IO) {
        // Return cached result if fresh
        if (worldNewsCacheValid()) {
            Log.d(TAG, "fetchWorldNewsGlobal: cache hit (${worldNewsCache!!.articles.size} articles)")
            return@withContext worldNewsCache!!.articles
        }

        val articles = mutableListOf<NewsArticle>()

        // Search top international English stories
        try {
            val url = "https://api.worldnewsapi.com/search-news" +
                "?api-key=$WORLD_NEWS_API_KEY" +
                "&language=en" +
                "&number=10" +
                "&sort=publish-time&sort-direction=DESC" +
                "&min-sentiment=-0.4"
            Log.d(TAG, "fetchWorldNewsGlobal: $url")
            val json    = JSONObject(httpGetSafe(url, mapOf("x-api-key" to WORLD_NEWS_API_KEY)))
            val newsArr = json.optJSONArray("news")
            if (newsArr != null) {
                for (i in 0 until newsArr.length()) {
                    articles.addIfValid(newsArr.getJSONObject(i), "global", preserveCategory = false)
                    if (articles.size >= 8) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchWorldNewsGlobal failed: ${e.message}")
        }

        Log.d(TAG, "fetchWorldNewsGlobal: ${articles.size} articles")
        val result = articles.distinctBy { it.title.take(60) }.take(8)
        if (result.isNotEmpty()) {
            worldNewsCache = NewsCacheEntry(result, System.currentTimeMillis(), "global")
        }
        result
    }

    /** Extension: parse a World News API article JSON object and add to list if valid */
    private fun MutableList<NewsArticle>.addIfValid(
        n: JSONObject,
        countryFallback: String,
        preserveCategory: Boolean
    ) {
        val title = n.optString("title", "").trim()
        if (title.isBlank()) return
        val sourceUrl  = n.optString("url", "")
        val sourceName = try {
            java.net.URL(sourceUrl).host.removePrefix("www.")
        } catch (_: Exception) { countryFallback.uppercase() }
        val rawCat  = n.optString("category", "")
        val category = if (preserveCategory) mapWorldNewsCategory(rawCat) else "World"
        val summary = n.optString("summary", "").trim().takeIf { it.isNotBlank() }
            ?: n.optString("text", "").take(220).trim()
        add(NewsArticle(
            title       = title,
            description = summary,
            source      = sourceName,
            url         = sourceUrl,
            publishedAt = formatNewsDate(n.optString("publish_date", "")),
            category    = category
        ))
    }

    /** World News API — best quality, real local news, supports 50+ countries
     *  Kept for backwards-compat reference; main logic now in fetchWorldNewsLocal/Global */
    private suspend fun fetchWorldNewsAPI(): List<NewsArticle> = fetchWorldNewsLocal()


    private fun mapWorldNewsCategory(raw: String): String = when (raw.lowercase()) {
        "politics", "government"            -> "Politics"
        "technology", "tech", "science"     -> "Technology"
        "business", "finance", "economy"    -> "Business"
        "sports", "sport"                   -> "Sports"
        "entertainment", "culture", "arts"  -> "Entertainment"
        "health", "medical"                 -> "Health"
        "environment", "climate"            -> "Environment"
        "world", "international"            -> "World"
        else                                -> if (raw.isNotBlank()) raw.replaceFirstChar { it.uppercaseChar() } else "General"
    }

    /** httpGet that safely handles non-2xx by reading errorStream instead of crashing */
    private fun httpGetSafe(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val conn = java.net.URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 12000
        conn.readTimeout    = 12000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            Log.e(TAG, "httpGetSafe HTTP $code for $urlStr: $err")
            throw Exception("HTTP $code: $err")
        }
    }

    private suspend fun fetchGeminiNews(): List<NewsArticle> = withContext(Dispatchers.IO) {
        val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        try {
            val today   = SimpleDateFormat("EEEE, MMMM d yyyy", Locale.US).format(Date())
            val cc      = cachedCountryCode.uppercase()
            val country = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() }
                ?: when (cc) {
                    "IN" -> "India"; "KR" -> "South Korea"; "US" -> "United States"
                    "GB" -> "United Kingdom"; "JP" -> "Japan"; "DE" -> "Germany"
                    "FR" -> "France"; "AU" -> "Australia"; "CA" -> "Canada"
                    else -> "India"
                }
            // Ultra-strict JSON-only prompt
            val prompt = """[{"title":"Placeholder"}]
Ignore that. Today is $today. Return ONLY a valid JSON array — nothing else, no text before or after.
Array of 8 news objects for $country readers. Each object: title, description, source, category, url.
Valid categories: World Technology Business Sports Science Health Entertainment Politics.
[""".trimIndent()

            Log.d(TAG, "fetchGeminiNews: country=$country cc=$cc")
            val body = buildGeminiRequest(prompt, 0.4, 1600)
            val raw  = callGemini(body)
            Log.d(TAG, "Gemini news raw (first 300): ${raw.take(300)}")

            // Robust extraction: find [ ... ] even if there's leading text
            val start = raw.indexOf('[')
            val end   = raw.lastIndexOf(']')
            if (start < 0 || end < 0 || end <= start) {
                Log.e(TAG, "Gemini news: no JSON array in response: ${raw.take(200)}")
                return@withContext buildOfflineNews(country, now)
            }
            val jsonStr = raw.substring(start, end + 1)
            val arr = try { JSONArray(jsonStr) } catch (je: Exception) {
                Log.e(TAG, "Gemini news: JSON parse failed: ${je.message} | json: ${jsonStr.take(200)}")
                return@withContext buildOfflineNews(country, now)
            }
            val articles = (0 until arr.length()).mapNotNull { i ->
                try {
                    val n = arr.getJSONObject(i)
                    val title = n.optString("title", "").trim()
                    if (title.isBlank()) return@mapNotNull null
                    NewsArticle(
                        title       = title,
                        description = n.optString("description", "").trim(),
                        source      = n.optString("source", "NowBrief AI").trim(),
                        url         = n.optString("url", ""),
                        publishedAt = now,
                        category    = n.optString("category", "General").trim()
                    )
                } catch (e: Exception) { null }
            }
            Log.d(TAG, "Gemini news: parsed ${articles.size} articles")
            if (articles.isNotEmpty()) articles else buildOfflineNews(country, now)
        } catch (e: Exception) {
            Log.e(TAG, "fetchGeminiNews error: ${e.message}", e)
            buildOfflineNews(
                Locale.getDefault().displayCountry.ifBlank { "your region" },
                now
            )
        }
    }

    /** Build plausible offline headlines when network/AI completely unavailable */
    private fun buildOfflineNews(country: String, time: String): List<NewsArticle> {
        val cats = listOf("World","Technology","Business","Health","Science","Sports","Entertainment","Politics")
        return listOf(
            NewsArticle("News unavailable — tap ↻ to retry",
                "Could not connect to news servers. Check your internet connection and try refreshing.",
                "NowBrief", "", time, "System"),
            NewsArticle("Tip: Make sure mobile data or Wi-Fi is active",
                "NowBrief needs a network connection to fetch news for $country.",
                "NowBrief", "", time, "System")
        )
    }

    /** Last-resort static news when both newsdata.io and Gemini fail */
    private fun hardcodedFallbackNews(time: String): List<NewsArticle> = listOf(
        NewsArticle("Could not load news", "Check your internet connection and tap ↻ to retry.",
            "NowBrief", "", time, "System"),
        NewsArticle("Tip: Grant all permissions", "Make sure NowBrief has network access and background activity allowed.",
            "NowBrief", "", time, "System")
    )

    // ── GEMINI SUMMARY ──────────────────────────────────────────────

    private suspend fun fetchGeminiSummary(
        weather: WeatherData,
        news: List<NewsArticle>
    ): NowBriefSummary = withContext(Dispatchers.IO) {
        try {
            val hour      = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when { hour < 12 -> "morning"; hour < 17 -> "afternoon"; hour < 21 -> "evening"; else -> "night" }
            val today     = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
            val newsTitles = news.take(5).mapIndexed { i, a -> "${i+1}. ${a.title}" }.joinToString("\n")

            val prompt = """
You are NowBrief, a warm friendly AI embedded in a Samsung Now Bar.
Today: $today ($timeOfDay, hour: $hour)
Weather: ${weather.condition}, ${weather.temperature.toInt()}°C feels like ${weather.feelsLike.toInt()}°C, rain ${weather.rainChance}%, UV ${weather.uvIndex}
City: ${weather.cityName}
Top headlines:
$newsTitles

Respond ONLY with a valid JSON object (no markdown, no backticks). Fill EVERY field:
{
  "greeting": "Warm $timeOfDay greeting 2-4 words e.g. 'Good morning'",
  "newsSummary": "Exactly 2 sentences summarizing the 2 most important headlines above. Name the specific events.",
  "daySummary": "Exactly 2 warm helpful sentences for this $timeOfDay based on weather and time.",
  "weatherSummary": "One natural sentence about today's weather.",
  "weatherAdvice": "One practical sentence: what to wear or carry today.",
  "quote": "Inspiring quote — Attribution",
  "tips": ["Weather-relevant tip", "Health or focus tip", "Evening or time-of-day tip"],
  "musicMood": "one word",
  "musicTitle": "Real song title fitting $timeOfDay mood",
  "musicArtist": "Artist name",
  "musicMoodTags": ["tag1", "tag2"],
  "musicReason": "Max 8 words why"
}
""".trimIndent()

            val text = callGemini(buildGeminiRequest(prompt, 0.85, 2000))
            val data = JSONObject(text)

            val tipsArr = data.optJSONArray("tips")
            val tips    = if (tipsArr != null) (0 until tipsArr.length()).map { tipsArr.getString(it) } else emptyList()

            val moodTagsArr = data.optJSONArray("musicMoodTags")
            val moodTags = if (moodTagsArr != null && moodTagsArr.length() >= 2)
                listOf(moodTagsArr.getString(0), moodTagsArr.getString(1))
            else listOf(data.optString("musicMood", "calm"), "Uplifting")

            NowBriefSummary(
                greeting       = data.optString("greeting", getContextualGreeting()),
                weatherSummary = data.optString("weatherSummary", ""),
                weatherAdvice  = data.optString("weatherAdvice", ""),
                daySummary     = data.optString("daySummary", ""),
                newsSummary    = data.optString("newsSummary", ""),
                // Use Gemini quote if returned, otherwise rotate from local pool
                quote          = data.optString("quote", "").ifBlank { getNextQuote() },
                tips           = tips,
                tip            = tips.firstOrNull() ?: "",
                musicMood      = data.optString("musicMood", "calm"),
                music = MusicRecommendation(
                    title  = data.optString("musicTitle", "Lovely Day"),
                    artist = data.optString("musicArtist", "Bill Withers"),
                    mood   = moodTags.joinToString(" · "),
                    reason = data.optString("musicReason", "Perfect for now")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchGeminiSummary error", e)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val fallbackMusic = when {
                hour < 6  -> MusicRecommendation("Clair de Lune", "Claude Debussy", "Peaceful · Calm", reason = "Perfect for late nights")
                hour < 12 -> MusicRecommendation("Here Comes the Sun", "The Beatles", "Uplifting · Morning", reason = "A sunny start to the day")
                hour < 17 -> MusicRecommendation("Lovely Day", "Bill Withers", "Happy · Bright", reason = "Keep your afternoon bright")
                hour < 21 -> MusicRecommendation("Golden Hour", "JVKE", "Warm · Evening", reason = "Wind down with warm vibes")
                else      -> MusicRecommendation("Night Owl", "Galimatias", "Mellow · Night", reason = "Ease into a restful night")
            }
            val weatherLine = "It's ${weather.temperature.toInt()}°C and ${weather.condition.lowercase()} in ${weather.cityName}."
            val adviceLine = when {
                weather.rainChance > 50 -> "Carry an umbrella — rain is likely today."
                weather.uvIndex >= 7    -> "Apply sunscreen before heading out."
                weather.temperature > 35 -> "Stay hydrated and avoid peak afternoon heat."
                weather.temperature < 15 -> "Layer up — it's chilly outside."
                else                    -> "Great conditions today — enjoy!"
            }
            val newsSummaryFallback = if (news.isNotEmpty())
                "Today's top story: ${news[0].title}." +
                if (news.size > 1) " Also: ${news[1].title.take(80)}." else ""
            else "Check the News tab for today's top stories."

            NowBriefSummary(
                greeting       = getContextualGreeting(),
                weatherSummary = weatherLine,
                weatherAdvice  = adviceLine,
                daySummary     = "Here's your brief for today. Stay on top of the weather and take care of yourself.",
                newsSummary    = newsSummaryFallback,
                quote          = getNextQuote(),
                tips           = listOf("Start your day with a glass of water.", "Take short breaks to recharge.", "Focus on one task at a time."),
                tip            = "Stay hydrated and take breaks.",
                musicMood      = "calm",
                music          = fallbackMusic
            )
        }
    }

    // ── HELPERS ─────────────────────────────────────────────────────

    private fun buildGeminiRequest(prompt: String, temperature: Double, maxTokens: Int): String {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", temperature)
                put("maxOutputTokens", maxTokens)
            })
        }.toString()
    }

    private fun callGemini(body: String): String {
        val resp = JSONObject(httpPost(GEMINI_URL, body))
        val raw = resp.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
        // Strip any markdown code fences Gemini sometimes wraps around JSON
        val stripped = raw
            .removePrefix("```json").removePrefix("```kotlin").removePrefix("```")
            .removeSuffix("```")
            .trim()
        // If there's still a fence inside (e.g. "Here is the JSON:\n```json...```"), extract content
        val fenceStart = stripped.indexOf("```")
        return if (fenceStart >= 0) {
            val inner = stripped.substring(fenceStart).removePrefix("```json").removePrefix("```").trim()
            inner.substringBefore("```").trim()
        } else {
            stripped
        }
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout    = 10000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod  = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput       = true
        conn.connectTimeout = 15000
        conn.readTimeout    = 15000
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "no error body"
            throw Exception("HTTP $code: $errBody")
        }
    }

    private fun wmoToCondition(code: Int): Pair<String, String> = when (code) {
        0        -> "Clear Sky"     to "☀️"
        1, 2     -> "Partly Cloudy" to "⛅"
        3        -> "Overcast"      to "☁️"
        45, 48   -> "Foggy"         to "🌫️"
        51,53,55 -> "Drizzle"       to "🌦️"
        61,63,65 -> "Rainy"         to "🌧️"
        71,73,75 -> "Snowy"         to "❄️"
        80,81,82 -> "Rain Showers"  to "🌦️"
        95       -> "Thunderstorm"  to "⛈️"
        96,99    -> "Heavy Storm"   to "🌩️"
        else     -> "Clear"         to "🌤️"
    }

    private fun formatTime(isoTime: String): String {
        return try {
            val sdf  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
            val date = sdf.parse(isoTime) ?: return isoTime
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
        } catch (e: Exception) { isoTime }
    }

    /**
     * newsdata.io pubDate is always UTC, e.g. "2025-05-10 06:30:00".
     * Parse strictly as UTC and diff against System.currentTimeMillis() (absolute).
     */
    private fun formatNewsDate(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val utc = TimeZone.getTimeZone("UTC")
            val date: Date = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "EEE, dd MMM yyyy HH:mm:ss Z"
            ).firstNotNullOfOrNull { fmt ->
                try { SimpleDateFormat(fmt, Locale.US).also { it.timeZone = utc }.parse(raw) }
                catch (_: Exception) { null }
            } ?: return raw.take(16)  // last resort: trim to readable length

            val diffMin = (System.currentTimeMillis() - date.time) / 60_000L
            when {
                diffMin < 1    -> "Just now"
                diffMin < 60   -> "${diffMin}m ago"
                diffMin < 1440 -> "${diffMin / 60}h ago"
                diffMin < 2880 -> "Yesterday"
                else           -> SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
            }
        } catch (e: Exception) { raw.take(16) }
    }
}
