package dev.rohit.labelaudit

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONObject

class FoodNotFound(message: String) : Exception(message)

enum class FoodSource(val label: String) {
    SAVED("Saved"),
    OFF("Open Food Facts"),
    FATSECRET("FatSecret"),
}

enum class LookupPhase {
    IDLE,
    LOADING,
    READY,
    NOT_FOUND,
    ERROR,
    NEEDS_KEY,
}

data class FoodChoice(val phase: LookupPhase, val json: String? = null, val note: String = "")

interface FoodProviders {
    fun off(code: String): JSONObject

    fun fatSecret(code: String, keys: FatSecretKeys): JSONObject

    fun searchOff(query: String, page: Int): FoodSearchPage =
        throw IllegalStateException("Search unavailable")

    fun searchFatSecret(query: String, page: Int, keys: FatSecretKeys): FoodSearchPage =
        throw IllegalStateException("Search unavailable")

    fun clearToken() {}
}

class AndroidFoodProviders(private val db: Data) : FoodProviders {
    private val fatSecret = FatSecretLookup()

    override fun off(code: String): JSONObject = FoodLookup.fetch(db, code)

    override fun fatSecret(code: String, keys: FatSecretKeys) = fatSecret.fetch(code, keys)

    override fun searchOff(query: String, page: Int) = OffFoodSearch.search(query, page)

    override fun searchFatSecret(query: String, page: Int, keys: FatSecretKeys) =
        fatSecret.search(query, page, keys)

    override fun clearToken() {
        fatSecret.clearToken()
    }
}

/** HTTPS only; never log request headers/bodies, OAuth responses or credential values. */
class FatSecretLookup(private val transport: ((String, String, String?) -> JSONObject)? = null) {
    private val tokenLock = Any()
    private var tokenRevision = 0
    private var accessToken = ""
    private var expiresAt = 0L
    private var tokenClient = ""
    private var tokenSecret = ""

    fun clearToken() =
        synchronized(tokenLock) {
            tokenRevision++
            accessToken = ""
            expiresAt = 0
            tokenClient = ""
            tokenSecret = ""
        }

    private fun request(url: String, authorization: String, body: String? = null): JSONObject {
        transport?.let {
            return it(url, authorization, body)
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", authorization)
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            when (c.responseCode) {
                200 -> {}
                401,
                403 ->
                    throw IllegalStateException(
                        "FatSecret access denied. Check credentials and IP restrictions in Settings."
                    )
                429 -> throw IllegalStateException("FatSecret is busy. Try again shortly.")
                else ->
                    throw IllegalStateException(
                        "Couldn't reach FatSecret. Saved foods still work offline."
                    )
            }
            val text =
                c.inputStream.bufferedReader().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n == -1) break
                        require(out.length + n <= Data.MAX_LOOKUP_BYTES) {
                            "FatSecret response too large"
                        }
                        out.append(buffer, 0, n)
                    }
                    out.toString()
                }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun token(keys: FatSecretKeys): String {
        val revision =
            synchronized(tokenLock) {
                if (
                    accessToken.isNotEmpty() &&
                        keys.clientId == tokenClient &&
                        keys.clientSecret == tokenSecret &&
                        System.currentTimeMillis() < expiresAt
                )
                    return accessToken
                tokenRevision
            }
        // Never hold a lock needed by Settings during a network request.
        val basic =
            Base64.getEncoder()
                .encodeToString(
                    (keys.clientId + ":" + keys.clientSecret).toByteArray(Charsets.UTF_8)
                )
        val response =
            request(
                "https://oauth.fatsecret.com/connect/token",
                "Basic $basic",
                "grant_type=client_credentials",
            )
        val token = response.optString("access_token")
        check(token.isNotEmpty()) { "FatSecret credentials were not accepted" }
        synchronized(tokenLock) {
            check(revision == tokenRevision) { "FatSecret credentials changed during lookup" }
            accessToken = token
            tokenClient = keys.clientId
            tokenSecret = keys.clientSecret
            expiresAt =
                System.currentTimeMillis() +
                    (response.optLong("expires_in", 3600) - 60).coerceAtLeast(0) * 1000
        }
        return token
    }

    fun fetch(code: String, keys: FatSecretKeys): JSONObject {
        val gtin = gtin13(code)
        val response =
            request(
                "https://platform.fatsecret.com/rest/food/barcode/find-by-id/v2?barcode=" +
                    URLEncoder.encode(gtin, "UTF-8") +
                    "&format=json",
                "Bearer " + token(keys),
            )
        response.optJSONObject("error")?.let {
            when (it.optInt("code")) {
                211 -> throw FoodNotFound("FatSecret didn't find this barcode")
                21 ->
                    throw IllegalStateException(
                        "FatSecret blocked this IP address. Check your account's IP restrictions."
                    )
                8,
                14 -> {
                    clearToken()
                    throw IllegalStateException(
                        "FatSecret access expired or was denied. Check Settings."
                    )
                }
                else ->
                    throw IllegalStateException(
                        "FatSecret lookup failed. Check account access or try again."
                    )
            }
        }
        return parse(code, response.getJSONObject("food"))
    }

    fun search(query: String, page: Int, keys: FatSecretKeys): FoodSearchPage {
        val url =
            "https://platform.fatsecret.com/rest/foods/search/v6?search_expression=" +
                URLEncoder.encode(query, "UTF-8") +
                "&page_number=$page&max_results=10&flag_default_serving=true&food_type=none&format=json"
        val response = request(url, "Bearer " + token(keys))
        response.optJSONObject("error")?.let {
            when (it.optInt("code")) {
                21 ->
                    throw IllegalStateException(
                        "FatSecret blocked this IP address. Check your account's IP restrictions."
                    )
                211 -> return FoodSearchPage(emptyList(), false)
                else ->
                    throw IllegalStateException(
                        "FatSecret search failed. Check account access or try again."
                    )
            }
        }
        return FoodSearchParsing.fatSecret(response, page)
    }

    companion object {
        fun gtin13(code: String): String {
            require(BarcodeValue.productCode(code) == code) { "Unsupported barcode" }
            if (code.length == 14) {
                if (code.startsWith("0")) return code.substring(1)
                throw FoodNotFound("FatSecret doesn't support this barcode format")
            }
            return code.padStart(13, '0')
        }

        fun parse(code: String, product: JSONObject): JSONObject {
            val raw = product.getJSONObject("servings").get("serving")
            val servings =
                if (raw is JSONObject) listOf(raw)
                else
                    (raw as org.json.JSONArray).let { a ->
                        (0 until a.length()).map { a.getJSONObject(it) }
                    }
            val serving =
                servings.firstOrNull { it.optInt("is_default") == 1 }
                    ?: servings.firstOrNull()
                    ?: throw FoodNotFound("FatSecret has no serving information")
            val food =
                JSONObject()
                    .put("provider", "fatsecret")
                    .put("providerFoodId", product.getString("food_id"))
                    .put("providerServingId", serving.optString("serving_id"))
                    .put("name", product.optString("food_name"))
                    .put("brand", product.optString("brand_name"))
                    .put("basis", 1)
                    .put("unit", "serving")
                    .put("servingLabel", serving.optString("serving_description"))
            if (code.isNotBlank()) food.put("barcode", code)
            food.put("providerType", product.optString("food_type"))
            val metric = serving.optDouble("metric_serving_amount", 0.0)
            if (metric.isFinite() && metric > 0) {
                if (serving.optString("metric_serving_unit") == "g")
                    food.put("servingGrams", metric)
                if (serving.optString("metric_serving_unit") == "ml") food.put("servingMl", metric)
                // Some beverages report "oz" ambiguously; never infer mass or fluid volume.
            }
            val keys =
                mapOf(
                    "kcal" to "calories",
                    "protein" to "protein",
                    "carbs" to "carbohydrate",
                    "fat" to "fat",
                    "fiber" to "fiber",
                    "vitamin-a" to "vitamin_a",
                    "vitamin-c" to "vitamin_c",
                    "vitamin-d" to "vitamin_d",
                    "calcium" to "calcium",
                    "iron" to "iron",
                    "potassium" to "potassium",
                    "sodium" to "sodium",
                )
            for ((target, source) in keys) {
                val value = serving.optDouble(source, Double.NaN)
                if (value.isFinite() && value >= 0)
                    food.put(
                        target,
                        value /
                            when (target) {
                                "vitamin-a",
                                "vitamin-d" -> 1000000.0
                                "vitamin-c",
                                "calcium",
                                "iron",
                                "potassium",
                                "sodium" -> 1000.0
                                else -> 1.0
                            },
                    )
            }
            return food
        }
    }
}
