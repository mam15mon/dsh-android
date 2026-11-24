package io.github.mam15mon.dsh.core.provider

import dagger.Lazy
import io.github.mam15mon.dsh.core.engine.EngineManager
import io.github.mam15mon.dsh.core.session.b
import io.github.mam15mon.dsh.core.session.o
import io.github.mam15mon.dsh.core.session.s
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/*
 * WorkBuddy (Tencent's CodeBuddy plan) in the provider editor. The token store, the sign-in, the client
 * headers and the request spellings live in the engine (js/packages/plugin-workbuddy, ENGINE-CONTRACT §2.8):
 * the app asks the engine for the sign-in page, the model list (`/v3/config`, not `/models`) and the
 * connection test, so the app never holds WorkBuddy's tokens and sends the same requests the chat does.
 */

/** One signed-in account of a build, with what is known of its credits (`workbuddy/state`, `workbuddy/usage`). */
data class WbAccount(
    val id: String,
    val name: String,
    /** The sign-in is gone: sign in again. */
    val expired: Boolean = false,
    /** The account the last chat went to. */
    val current: Boolean = false,
    /** Why the account rests (`rate`, `credits`, `restricted`) and until when (epoch ms); null = it serves. */
    val restingWhy: String? = null,
    val restingUntil: Long? = null,
    val plan: String? = null,
    /** Credits still to spend, the plan's total and what is used; null = not read yet or not shown for this account. */
    val left: Double? = null,
    val total: Double? = null,
    val used: Double? = null,
    val creditsError: String? = null,
)

/** One build's sign-ins as the engine reports them (`workbuddy/state`). */
data class WbState(
    val signedIn: Boolean = false,
    val name: String? = null,
    /** Every sign-in lapsed and cannot be renewed: sign in again. */
    val expired: Boolean = false,
    val attempt: WbAttempt? = null,
    val accounts: List<WbAccount> = emptyList(),
)

/** A browser sign-in under way or just settled: [phase] is `waiting`, `done`, `failed` or `cancelled`. */
data class WbAttempt(val id: String, val phase: String, val url: String?, val error: String?) {
    val waiting get() = phase == "waiting"
}

fun parseWbAttempt(o: JsonObject): WbAttempt = WbAttempt(o.s("id").orEmpty(), o.s("phase").orEmpty(), o.s("url"), o.s("error"))

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

fun parseWbAccount(o: JsonObject): WbAccount {
    val rest = o.o("resting")
    return WbAccount(
        id = o.s("id").orEmpty(), name = o.s("name").orEmpty(), expired = o.b("expired") == true, current = o.b("current") == true,
        restingWhy = rest?.s("why"), restingUntil = rest?.num("until")?.toLong(),
        plan = o.s("plan"), left = o.num("left"), total = o.num("total"), used = o.num("used"), creditsError = o.s("creditsError"),
    )
}

fun parseWbState(e: JsonElement): WbState {
    val o = e as? JsonObject ?: return WbState()
    return WbState(
        signedIn = o.b("signedIn") == true,
        name = o.s("name"),
        expired = o.b("expired") == true,
        attempt = o.o("attempt")?.let(::parseWbAttempt),
        accounts = (o["accounts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseWbAccount) },
    )
}

/** A credit count as the card says it: whole, else to two places. */
fun wbCompact(n: Double): String = if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, n).trimEnd('0').trimEnd('.')

/**
 * The 推理控制 a WorkBuddy model gets from its config: null = the listing did not say (the engine's family table
 * decides), no levels = none, else the levels. DSH's level names are fixed, so the gateway's `none` (thinking off)
 * is DSH's `minimal`; the engine plugin turns it back (js plugin-workbuddy `applyEffort`).
 */
fun wbReasoningDecl(efforts: List<String>?): ReasoningDecl? {
    if (efforts == null) return null
    val levels = efforts.map { if (it == "none") "minimal" else it }.filter { it in ReasoningLevels.EFFORT }.distinct()
    return if (levels.isEmpty()) ReasoningDecl(ReasoningControl.None) else ReasoningDecl(ReasoningControl.Effort, levels)
}

/** `workbuddy/models`' answer as editor entries: id, the config's input window and output cap, image input. */
fun parseWbModels(e: JsonElement): List<RemoteModel> {
    val arr = (e as? JsonObject)?.get("models") as? JsonArray ?: return emptyList()
    return arr.mapNotNull { m ->
        val o = m as? JsonObject ?: return@mapNotNull null
        val id = o.s("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        RemoteModel(id, contextWindow = o.long("context"), maxTokens = o.long("output")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            images = o.b("images"),
            efforts = (o["efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, effort = o.s("effort"))
    }.distinctBy { it.id }
}

private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

/**
 * The engine's connection-test answer as the failure the editor shows (null = a tool call came back):
 * a 2xx stream is read like every other probe, anything else is classified from its status and body.
 */
fun wbProbeFailure(status: Int, body: String): VerifyFailure? =
    if (status in 200..299) readProbeAnswer(body.lineSequence()) else VerifyErrors.classify(status, body)

@Singleton
class WorkBuddyService @Inject constructor(private val engine: Lazy<EngineManager>) {
    private suspend fun call(method: String, params: JsonObject): JsonElement = engine.get().control().request(method, params)
    private fun params(site: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("site", site); build() }

    suspend fun state(site: String): WbState = parseWbState(call("workbuddy/state", params(site)))

    /** Starts a browser sign-in: the page to open, or the engine's reason it cannot (the message of the thrown error). */
    suspend fun startSignIn(site: String): WbAttempt {
        engine.get().cancelIdleShutdown()
        return parseWbState(buildJsonObject { put("attempt", call("workbuddy/sign-in/start", params(site))) }).attempt
            ?: error("WorkBuddy gave no sign-in page")
    }

    suspend fun cancel(site: String): WbState = parseWbState(call("workbuddy/sign-in/cancel", params(site))).also { settled() }

    /** A sign-in attempt ended (done, failed, cancelled): the engine may idle again. */
    fun settled() = engine.get().scheduleIdleShutdown()
    /** Signs one account (or, with [account] null, the whole build) out. */
    suspend fun signOut(site: String, account: String? = null): WbState =
        parseWbState(call("workbuddy/sign-out", params(site) { account?.let { put("account", it) } }))

    /** [state] with each account's credits read afresh (a request per account). */
    suspend fun usage(site: String): WbState = parseWbState(call("workbuddy/usage", params(site)))

    /** The build's models for [key] (or the stored sign-in when blank). */
    suspend fun models(site: String, key: String?): List<RemoteModel> =
        parseWbModels(call("workbuddy/models", params(site) { key?.trim()?.takeIf { it.isNotEmpty() }?.let { put("key", it) } }))

    /** One connection-test chat with a tool (ModelVerify.kt's probe, through the engine); null = passed. */
    suspend fun probe(site: String, key: String?, model: String, forceTool: Boolean): VerifyFailure? = try {
        val r = call("workbuddy/probe", params(site) {
            key?.trim()?.takeIf { it.isNotEmpty() }?.let { put("key", it) }
            put("model", model); put("forceTool", forceTool)
        }) as? JsonObject
        wbProbeFailure((r?.get("status") as? JsonPrimitive)?.longOrNull?.toInt() ?: 0, r?.s("body").orEmpty())
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        VerifyFailure.Server(0, t.message ?: t.javaClass.simpleName)
    }
}
