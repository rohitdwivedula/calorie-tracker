package dev.rohit.labelaudit

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class FoodSearchHit(val source: FoodSource, val key: String, val json: String)

data class FoodSearchPage(val hits: List<FoodSearchHit>, val hasMore: Boolean)

data class FoodSearchState(
    val phase: LookupPhase = LookupPhase.IDLE,
    val hits: List<FoodSearchHit> = emptyList(),
    val page: Int = -1,
    val hasMore: Boolean = false,
    val note: String = "",
)

/** Search previews remain ephemeral. Only selected foods become recorded snapshots. */
object FoodSearchParsing {
    private fun objects(value: Any?): List<JSONObject> =
        when (value) {
            is JSONObject -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            else -> emptyList()
        }

    fun off(response: JSONObject, page: Int): FoodSearchPage {
        val products = objects(response.opt("products") ?: response.opt("hits"))
        val hits =
            products.mapNotNull { product ->
                val code =
                    BarcodeValue.productCode(product.optString("code")) ?: return@mapNotNull null
                if (product.optString("product_name").isBlank()) return@mapNotNull null
                try {
                    val food =
                        FoodLookup.parseProduct(code, product, null)
                            .put("provider", "openfoodfacts")
                    FoodSearchHit(FoodSource.OFF, "off-$code", food.toString())
                } catch (_: Exception) {
                    null
                }
            }
        val total = response.optLong("count", -1)
        return FoodSearchPage(
            hits,
            if (total >= 0) page * 10L + products.size < total else products.size >= 10,
        )
    }

    fun fatSecret(response: JSONObject, page: Int): FoodSearchPage {
        val root = response.getJSONObject("foods_search")
        val products = objects(root.optJSONObject("results")?.opt("food"))
        val hits =
            products
                .mapNotNull { product ->
                    if (product.optString("food_name").isBlank()) return@mapNotNull null
                    try {
                        val food = FatSecretLookup.parse("", product)
                        FoodSearchHit(
                            FoodSource.FATSECRET,
                            "fs-${food.getString("providerFoodId")}",
                            food.toString(),
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy {
                    if (JSONObject(it.json).optString("providerType") == "Generic") 0 else 1
                }
        return FoodSearchPage(
            hits,
            (page + 1) * 10L < root.optLong("total_results", products.size.toLong()),
        )
    }

    fun savedMatches(food: JSONObject, query: String): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val text =
            listOf("name", "brand", "searchAlias")
                .joinToString(" ") { food.optString(it) }
                .lowercase()
        return words.all { text.contains(it) }
    }
}

object OffFoodSearch {
    private val fields =
        listOf(
            "code",
            "product_name",
            "brands",
            "nutriments",
            "serving_size",
            "serving_quantity",
            "serving_quantity_unit",
            "nutrition_data_per",
        )

    @JvmOverloads
    fun search(
        query: String,
        page: Int,
        transport: (String, String?) -> JSONObject = ::request,
    ): FoodSearchPage {
        // OFF's dedicated full-text service supports POST so query text need not be in URLs.
        val request =
            JSONObject()
                .put("q", query)
                .put("page_size", 10)
                .put("page", page + 1)
                .put("fields", JSONArray(fields))
                .put("langs", JSONArray().put("en"))
        val result = transport("https://search.openfoodfacts.org/search", request.toString())
        val hits = result.optJSONArray("hits") ?: JSONArray()
        val previews =
            (0 until hits.length())
                .mapNotNull { hits.optJSONObject(it) }
                .filter { BarcodeValue.productCode(it.optString("code")) != null }
                .distinctBy { it.optString("code").padStart(14, '0') }
        if (previews.isEmpty())
            return FoodSearchPage(emptyList(), (page + 1) * 10L < result.optLong("count", 0))
        val codes = previews.joinToString(",") { it.getString("code") }
        // Search indexes can lack label nutrition. One batch request supplies current
        // nutrition for the whole page rather than one API request per result tile.
        val details =
            try {
                transport(
                        "https://world.openfoodfacts.org/api/v2/search?code=" +
                            URLEncoder.encode(codes, "UTF-8") +
                            "&page_size=10&fields=" +
                            fields.joinToString(","),
                        null,
                    )
                    .optJSONArray("products") ?: JSONArray()
            } catch (_: Exception) {
                JSONArray()
            }
        val byCode =
            (0 until details.length())
                .mapNotNull { details.optJSONObject(it) }
                .associateBy { it.optString("code").padStart(14, '0') }
        val products = JSONArray()
        for (preview in previews) {
            val detailed = byCode[preview.getString("code").padStart(14, '0')]
            val product = JSONObject((detailed ?: preview).toString())
            if (product.optString("product_name").isBlank())
                product.put("product_name", preview.optString("product_name"))
            (product.opt("brands") as? JSONArray)?.let { brands ->
                product.put(
                    "brands",
                    (0 until brands.length()).joinToString(", ") { brands.optString(it) },
                )
            }
            products.put(product)
        }
        val parsed =
            FoodSearchParsing.off(
                JSONObject()
                    .put("products", products)
                    .put("count", result.optLong("count", previews.size.toLong())),
                page,
            )
        val pageCount =
            result.optInt(
                "page_count",
                ((result.optLong("count", previews.size.toLong()) + 9) / 10).toInt(),
            )
        return parsed.copy(hasMore = page + 1 < pageCount)
    }

    private fun request(url: String, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "BatchNutrition/1.0 (personal Android tracker)")
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(c.responseCode != 429) {
                "Open Food Facts search limit reached. Try again shortly."
            }
            check(c.responseCode == 200) {
                "Couldn't reach Open Food Facts. Saved foods still work offline."
            }
            val bytes =
                c.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        check(output.size() + read <= Data.MAX_LOOKUP_BYTES) {
                            "Search response too large"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8")
                }
            return JSONObject(bytes)
        } finally {
            c.disconnect()
        }
    }
}
