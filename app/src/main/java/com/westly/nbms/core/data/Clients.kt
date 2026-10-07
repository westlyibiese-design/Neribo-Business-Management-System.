package com.westly.nbms.core.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.westly.nbms.BuildConfig
import com.westly.nbms.core.session.SecureStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Keeps the Supabase login session in the encrypted [SecureStore]. */
internal class SecureSupabaseSessionManager(
    private val store: SecureStore
) : io.github.jan.supabase.auth.SessionManager {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        store.putString(SecureStore.KEY_SUPABASE_SESSION, json.encodeToString(UserSession.serializer(), session))
    }

    override suspend fun loadSession(): UserSession? {
        val raw = store.getString(SecureStore.KEY_SUPABASE_SESSION) ?: return null
        return try {
            json.decodeFromString(UserSession.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteSession() {
        store.remove(SecureStore.KEY_SUPABASE_SESSION)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ClientsModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(store: SecureStore): SupabaseClient =
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                sessionManager = SecureSupabaseSessionManager(store)
            }
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        val firestore = FirebaseFirestore.getInstance()
        try {
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        } catch (e: IllegalStateException) {
            // Settings were already applied earlier in this process. Safe to ignore.
        }
        return firestore
    }

    @Provides
    @Singleton
    fun provideFirebaseDatabase(): FirebaseDatabase {
        val database = FirebaseDatabase.getInstance()
        try {
            database.setPersistenceEnabled(true)
        } catch (e: Exception) {
            // Already initialised. Safe to ignore.
        }
        return database
    }
}
