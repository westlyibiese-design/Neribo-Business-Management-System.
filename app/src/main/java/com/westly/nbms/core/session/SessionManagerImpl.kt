package com.westly.nbms.core.session

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.westly.nbms.BuildConfig
import com.westly.nbms.core.rbac.Rbac
import com.westly.nbms.core.rbac.Role
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class MemberRow(
    @SerialName("user_id") val userId: String,
    @SerialName("business_id") val businessId: String,
    val role: String,
    val name: String = "",
    val email: String? = null,
    val phone: String? = null,
    val status: String = "active",
    @SerialName("uses_pin") val usesPin: Boolean = false
)

@Serializable
private data class BusinessRow(
    val id: String,
    val name: String,
    val code: String,
    @SerialName("business_type") val businessType: String = "hotel",
    val currency: String = "NGN",
    @SerialName("currency_symbol") val currencySymbol: String = "₦",
    val timezone: String = "Africa/Lagos",
    @SerialName("maintenance_mode") val maintenanceMode: Boolean = false,
    @SerialName("maintenance_message") val maintenanceMessage: String? = null
)

@Serializable
private data class RoleRow(val role: String)

private sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult
    data object Suspended : TokenResult
    data object Unauthorized : TokenResult
    data object Network : TokenResult
    data class Other(val message: String) : TokenResult
}

private const val MSG_NETWORK_NO_ACCESS = "Can't reach the server. Check your connection and try again."
private const val MSG_SUSPENDED = "This account has been suspended. Contact your administrator."
/** Every network step gives up after this long, so the app can never sit on the loading screen forever. */
private const val TOKEN_TIMEOUT_MS = 10_000L
private const val FIREBASE_TIMEOUT_MS = 10_000L
private const val QUERY_TIMEOUT_MS = 12_000L

/** If the app is still "Loading" this long after it started, it stops waiting and decides on its own. */
private const val STARTUP_WATCHDOG_MS = 10_000L

private const val MSG_LIVE_SESSION = "Could not start your live data session. Please try again in a moment."

/**
 * Keeps track of who is signed in, in which business, with which role and modules.
 * See the Phase 3 specification (sections 2.4 and 2.5) for the full state machine.
 */
@Singleton
class SessionManagerImpl @Inject constructor(
    private val supabase: SupabaseClient,
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val store: SecureStore
) : SessionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private val bootstrapMutex = Mutex()

    private val notConnected = BuildConfig.SUPABASE_URL.contains("placeholder")

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private var liveJob: Job? = null
    private var tokenRefreshJob: Job? = null
    private var lastFailure: String? = null

    init {
        if (notConnected) {
            _state.value = SessionState.SignedOut
        } else {
            scope.launch { watchSupabaseSession() }
            scope.launch {
                delay(STARTUP_WATCHDOG_MS)
                if (_state.value is SessionState.Loading) resolveStuckStartup()
            }
        }
    }

    /**
     * The Supabase client never reported a result (for example it could not refresh an old session on a bad network).
     * With a saved session we try to open it; without one we show the sign-in screen.
     */
    private suspend fun resolveStuckStartup() {
        val hasSession = try {
            supabase.auth.currentSessionOrNull() != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (hasSession) {
            bootstrap(force = false)
        } else if (_state.value is SessionState.Loading) {
            _state.value = SessionState.SignedOut
        }
    }

    /** Runs a network call but never waits longer than [QUERY_TIMEOUT_MS]; a timeout becomes an IOException. */
    private suspend fun <T> net(block: suspend () -> T): T {
        var done = false
        var value: T? = null
        withTimeoutOrNull(QUERY_TIMEOUT_MS) {
            value = block()
            done = true
        }
        if (!done) throw IOException("The server took too long to answer.")
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private suspend fun firebaseSignIn(token: String) {
        var done = false
        withTimeoutOrNull(FIREBASE_TIMEOUT_MS) {
            firebaseAuth.signInWithCustomToken(token).await()
            done = true
        }
        if (!done) throw FirebaseNetworkException("Timed out signing in to the live data service.")
    }

    // ---------------------------------------------------------------- public API

    override suspend fun signInWithPassword(email: String, password: String): Result<Unit> {
        if (notConnected) {
            return Result.failure(IllegalStateException("The app is not connected to its server yet."))
        }
        lastFailure = null
        try {
            supabase.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            val message = if (e.statusCode in 400..499) "Invalid email or password."
            else "Something went wrong signing in. Please try again."
            return Result.failure(IllegalStateException(message))
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("Can't reach the server. Check your connection."))
        }
        val failure = bootstrap(force = false)
        return if (failure != null) Result.failure(IllegalStateException(failure)) else Result.success(Unit)
    }

    override suspend fun signInWithPin(businessCode: String, pin: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("PIN login is added in Phase 5"))

    override suspend fun signOut() {
        stopLive()
        try { supabase.auth.signOut() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { firebaseAuth.signOut() } catch (_: Exception) { }
        store.clearExcept(SecureStore.KEY_LAST_BUSINESS_CODE)
        _state.value = SessionState.SignedOut
    }

    override suspend fun refresh() {
        if (notConnected) return
        bootstrap(force = true)
    }

    /** Used by Phase 5: after a PIN session is imported into Supabase, this reads the membership and business. */
    internal suspend fun adoptSupabaseSession() {
        bootstrap(force = true)
    }

    // ---------------------------------------------------------------- Supabase session watching

    private suspend fun watchSupabaseSession() {
        supabase.auth.sessionStatus.collect { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    if (status.source is SessionSource.Refresh) {
                        if (_state.value is SessionState.SignedIn) silentTokenRefresh()
                    } else {
                        bootstrap(force = false)
                    }
                }
                is SessionStatus.NotAuthenticated -> {
                    val current = _state.value
                    if (current is SessionState.Loading || current is SessionState.SignedIn) {
                        stopLive()
                        try { firebaseAuth.signOut() } catch (_: Exception) { }
                        _state.value = SessionState.SignedOut
                    }
                }
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- bootstrap

    /** Returns a message when the attempt ended signed out or unreachable, otherwise null. */
    private suspend fun bootstrap(force: Boolean): String? = bootstrapMutex.withLock {
        val uid = supabase.auth.currentSessionOrNull()?.user?.id
        if (uid == null) {
            _state.value = SessionState.SignedOut
            return@withLock lastFailure
        }
        val before = _state.value
        if (!force && before is SessionState.SignedIn && before.user.uid == uid) return@withLock null
        // A sign-in that already ended in "no access" is not retried again by the second caller.
        if (!force && before is SessionState.NoAccess) return@withLock lastFailure
        if (force) lastFailure = null
        // Keep the sign-in screen visible while a sign-in is in progress; only the very first start shows Loading.
        if (before is SessionState.NoAccess) _state.value = SessionState.Loading

        when (val result = obtainToken(silent = false)) {
            is TokenResult.Suspended -> {
                goNoAccess(MSG_SUSPENDED)
                return@withLock null
            }
            is TokenResult.Unauthorized -> {
                failSignedOut("Your session has expired. Please sign in again.")
                return@withLock lastFailure
            }
            is TokenResult.Network -> {
                goNoAccess(MSG_NETWORK_NO_ACCESS)
                lastFailure = MSG_NETWORK_NO_ACCESS
                return@withLock lastFailure
            }
            is TokenResult.Other -> {
                goNoAccess(result.message)
                lastFailure = result.message
                return@withLock lastFailure
            }
            is TokenResult.Ok -> {
                try {
                    firebaseSignIn(result.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FirebaseNetworkException) {
                    goNoAccess(MSG_NETWORK_NO_ACCESS)
                    lastFailure = MSG_NETWORK_NO_ACCESS
                    return@withLock lastFailure
                } catch (e: Exception) {
                    goNoAccess(MSG_LIVE_SESSION)
                    lastFailure = MSG_LIVE_SESSION
                    return@withLock lastFailure
                }
            }
        }

        // Read membership + business from Supabase.
        try {
            val member = net {
                supabase.from("business_members")
                    .select(Columns.list("user_id", "business_id", "role", "name", "email", "phone", "status", "uses_pin")) {
                        filter { eq("user_id", uid) }
                    }
                    .decodeSingleOrNull<MemberRow>()
            }
            if (member == null) {
                goNoAccess("No business account found for this user.")
                return@withLock null
            }
            val business = net {
                supabase.from("businesses")
                    .select(
                        Columns.list(
                            "id", "name", "code", "business_type", "currency", "currency_symbol",
                            "timezone", "maintenance_mode", "maintenance_message"
                        )
                    ) {
                        filter { eq("id", member.businessId) }
                    }
                    .decodeSingleOrNull<BusinessRow>()
            }
            if (business == null) {
                goNoAccess("No business account found for this user.")
                return@withLock null
            }
            val roleRows = net {
                supabase.from("business_roles")
                    .select(Columns.list("role")) {
                        filter {
                            eq("business_id", member.businessId)
                            eq("enabled", true)
                        }
                    }
                    .decodeList<RoleRow>()
            }

            val role = Role.fromKey(member.role)
            if (role == null) {
                goNoAccess("This account's role is not recognised. Contact your administrator.")
                return@withLock null
            }
            if (member.status != "active") {
                goNoAccess(MSG_SUSPENDED)
                return@withLock null
            }
            val enabledRoles = roleRows.mapNotNull { Role.fromKey(it.role) }.toSet()
            val signedIn = SessionState.SignedIn(
                user = SessionUser(
                    uid = member.userId,
                    businessId = member.businessId,
                    role = role,
                    name = member.name,
                    email = member.email,
                    phone = member.phone,
                    status = member.status,
                    usesPin = member.usesPin
                ),
                business = Business(
                    id = business.id,
                    name = business.name,
                    code = business.code,
                    enabledRoles = enabledRoles,
                    currency = business.currency,
                    currencySymbol = business.currencySymbol,
                    timezone = business.timezone,
                    businessType = business.businessType,
                    maintenanceMode = business.maintenanceMode,
                    maintenanceMessage = business.maintenanceMessage
                ),
                modules = Rbac.enabledModules(enabledRoles)
            )
            if (business.code.isNotBlank()) store.putString(SecureStore.KEY_LAST_BUSINESS_CODE, business.code)
            _state.value = signedIn
            startLive(signedIn)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            goNoAccess(MSG_NETWORK_NO_ACCESS)
            lastFailure = MSG_NETWORK_NO_ACCESS
            lastFailure
        }
    }

    private fun goNoAccess(reason: String) {
        stopLive()
        _state.value = SessionState.NoAccess(reason)
    }

    private suspend fun failSignedOut(message: String) {
        lastFailure = message
        stopLive()
        try { supabase.auth.signOut() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { firebaseAuth.signOut() } catch (_: Exception) { }
        store.clearExcept(SecureStore.KEY_LAST_BUSINESS_CODE)
        _state.value = SessionState.SignedOut
    }

    // ---------------------------------------------------------------- Firebase token

    private suspend fun obtainToken(silent: Boolean): TokenResult {
        var attempt = 0
        while (true) {
            val result = fetchFirebaseToken()
            if (result !is TokenResult.Network || silent || attempt >= 1) return result
            delay(2_000L) // one quick retry, then give up
            attempt++
        }
    }

    private suspend fun fetchFirebaseToken(): TokenResult {
        if (supabase.auth.currentAccessTokenOrNull() == null) return TokenResult.Unauthorized
        return try {
            // The Supabase client already sends the signed-in user's access token. Adding a second
            // Authorization header makes the server see "Not a JWT" and answer 401.
            var outcome: TokenResult? = null
            withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
                val response = supabase.functions.invoke(
                    function = "firebase-token",
                    body = JsonObject(emptyMap())
                )
                val text = response.bodyAsText()
                outcome = if (response.status.isSuccess()) parseTokenBody(text) else classify(response.status.value, text)
            }
            outcome ?: TokenResult.Network
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            classify(e.statusCode, e.message.orEmpty())
        } catch (e: Exception) {
            TokenResult.Network
        }
    }

    private fun parseTokenBody(text: String): TokenResult = try {
        val obj = json.parseToJsonElement(text).jsonObject
        val ok = obj["ok"]?.jsonPrimitive?.boolean ?: false
        val token = obj["token"]?.jsonPrimitive?.contentOrNull
        if (ok && !token.isNullOrBlank()) TokenResult.Ok(token)
        else classify(400, obj["error"]?.jsonPrimitive?.contentOrNull ?: text)
    } catch (e: Exception) {
        TokenResult.Other(MSG_LIVE_SESSION)
    }

    private fun classify(status: Int, body: String): TokenResult {
        val lower = body.lowercase()
        return when {
            "suspended" in lower -> TokenResult.Suspended
            status == 401 || status == 403 -> TokenResult.Unauthorized
            status >= 500 -> TokenResult.Other(MSG_LIVE_SESSION)
            else -> TokenResult.Other(MSG_LIVE_SESSION)
        }
    }

    /** Refreshes the Firebase session without any visible state change. Failures are ignored. */
    private suspend fun silentTokenRefresh() {
        if (_state.value !is SessionState.SignedIn) return
        when (val result = obtainToken(silent = true)) {
            is TokenResult.Ok -> try {
                firebaseSignIn(result.token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
            is TokenResult.Suspended -> {
                goNoAccess(MSG_SUSPENDED)
                try { supabase.auth.signOut() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                try { firebaseAuth.signOut() } catch (_: Exception) { }
            }
            is TokenResult.Unauthorized -> {
                failSignedOut("Your session has expired. Please sign in again.")
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- live updates

    private fun startLive(signedIn: SessionState.SignedIn) {
        stopLive()
        val bid = signedIn.business.id
        val uid = signedIn.user.uid
        liveJob = scope.launch {
            launch {
                documentFlow("businesses/$bid").collect { applyBusinessSnapshot(uid, it) }
            }
            launch {
                documentFlow("businesses/$bid/users/$uid").collect { snap ->
                    if (snap.exists() && snap.getString("status") == "suspended") {
                        goNoAccess(MSG_SUSPENDED)
                        try { supabase.auth.signOut() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        try { firebaseAuth.signOut() } catch (_: Exception) { }
                    }
                }
            }
        }
        tokenRefreshJob = scope.launch {
            while (true) {
                delay(50L * 60L * 1000L)
                silentTokenRefresh()
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }

    private fun documentFlow(path: String): Flow<DocumentSnapshot> = callbackFlow {
        val registration = try {
            firestore.document(path).addSnapshotListener { snapshot, error ->
                if (error == null && snapshot != null) trySend(snapshot)
            }
        } catch (e: Exception) {
            null
        }
        awaitClose { registration?.remove() }
    }

    private fun applyBusinessSnapshot(uid: String, snap: DocumentSnapshot) {
        if (!snap.exists()) return
        _state.update { current ->
            if (current !is SessionState.SignedIn || current.user.uid != uid) return@update current
            val old = current.business
            val rolesRaw = snap.get("enabledRoles") as? List<*>
            val enabled = rolesRaw?.mapNotNull { Role.fromKey(it as? String) }?.toSet() ?: old.enabledRoles
            val updated = old.copy(
                name = snap.getString("name") ?: old.name,
                currency = snap.getString("currency") ?: old.currency,
                currencySymbol = snap.getString("currencySymbol") ?: old.currencySymbol,
                timezone = snap.getString("timezone") ?: old.timezone,
                enabledRoles = enabled,
                maintenanceMode = snap.getBoolean("maintenanceMode") ?: old.maintenanceMode,
                maintenanceMessage = if (snap.contains("maintenanceMessage")) snap.getString("maintenanceMessage") else old.maintenanceMessage
            )
            if (updated == old) current else current.copy(business = updated, modules = Rbac.enabledModules(enabled))
        }
    }
}
