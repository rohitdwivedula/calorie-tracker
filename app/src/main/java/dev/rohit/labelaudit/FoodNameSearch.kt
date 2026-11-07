package dev.rohit.labelaudit

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Foreground, explicitly submitted search. A slow provider never hides the other's results. */
class FoodNameSearch(
    private val scope: CoroutineScope,
    private val providers: () -> FoodProviders,
    private val keys: () -> FatSecretKeys?,
    private val configured: () -> Boolean,
    initialQuery: String = "",
    private val rememberQuery: (String) -> Unit = {},
) {
    var query by mutableStateOf(initialQuery)
        private set

    var submitted by mutableStateOf("")
        private set

    var states by mutableStateOf(emptyMap<FoodSource, FoodSearchState>())
        private set

    val loading
        get() = states.values.any { it.phase == LookupPhase.LOADING }

    private var generation = 0
    private var credentialRevision = 0
    private val jobs = mutableListOf<Job>()

    fun state(source: FoodSource) =
        states[source]
            ?: FoodSearchState(
                if (source == FoodSource.FATSECRET && !configured()) LookupPhase.NEEDS_KEY
                else LookupPhase.IDLE
            )

    fun updateQuery(value: String) {
        val next = value.take(120)
        if (next == query) return
        cancel()
        query = next
        rememberQuery(next)
        submitted = ""
        states = emptyMap()
    }

    fun search() {
        val term = query.trim()
        if (term.length < 2 || (term == submitted && loading)) return
        cancel()
        submitted = term
        states = emptyMap()
        load(FoodSource.OFF, 0)
        if (configured()) load(FoodSource.FATSECRET, 0)
        else states = states + (FoodSource.FATSECRET to FoodSearchState(LookupPhase.NEEDS_KEY))
    }

    fun more(source: FoodSource? = null) {
        for (provider in listOf(FoodSource.OFF, FoodSource.FATSECRET)) {
            val current = state(provider)
            if (
                (source == null || source == provider) &&
                    current.hasMore &&
                    current.phase != LookupPhase.LOADING
            )
                load(provider, current.page + 1)
        }
    }

    fun retry(source: FoodSource) {
        val current = state(source)
        if (
            submitted.isBlank() || submitted != query.trim() || current.phase == LookupPhase.LOADING
        )
            return
        load(source, if (current.hasMore && current.hits.isNotEmpty()) current.page + 1 else 0)
    }

    fun credentialsChanged() {
        credentialRevision++
        states =
            states +
                (FoodSource.FATSECRET to
                    FoodSearchState(if (configured()) LookupPhase.IDLE else LookupPhase.NEEDS_KEY))
        if (configured() && submitted.isNotBlank() && submitted == query.trim())
            load(FoodSource.FATSECRET, 0)
    }

    private fun load(source: FoodSource, page: Int) {
        val epoch = generation
        val keyRevision = credentialRevision
        val term = submitted
        val previous = if (page == 0) FoodSearchState() else state(source)
        states = states + (source to previous.copy(phase = LookupPhase.LOADING, note = ""))
        jobs.removeAll { it.isCompleted }
        jobs +=
            scope.launch {
                val result =
                    try {
                        val response =
                            withContext(Dispatchers.IO) {
                                if (source == FoodSource.OFF) providers().searchOff(term, page)
                                else
                                    providers()
                                        .searchFatSecret(
                                            term,
                                            page,
                                            keys() ?: error("Add FatSecret credentials in Settings"),
                                        )
                            }
                        previous.copy(
                            phase = LookupPhase.READY,
                            hits = (previous.hits + response.hits).distinctBy { it.key },
                            page = page,
                            hasMore = response.hasMore,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        previous.copy(
                            phase = LookupPhase.ERROR,
                            note =
                                if (e is IllegalStateException) e.message ?: "Search failed"
                                else
                                    "Couldn't reach ${source.label}. Saved foods still work offline.",
                        )
                    }
                if (
                    epoch != generation ||
                        (source == FoodSource.FATSECRET &&
                            (keyRevision != credentialRevision || !configured()))
                )
                    return@launch
                states = states + (source to result)
            }
    }

    fun cancel() {
        generation++
        jobs.forEach { it.cancel() }
        jobs.removeAll { it.isCompleted }
    }

    suspend fun resetForRestore() {
        cancel()
        jobs.toList().forEach { it.join() }
        query = ""
        submitted = ""
        states = emptyMap()
        rememberQuery("")
    }
}
