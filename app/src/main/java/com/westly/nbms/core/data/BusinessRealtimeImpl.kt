package com.westly.nbms.core.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BusinessRealtimeImpl @Inject constructor(
    private val database: FirebaseDatabase,
    private val session: SessionManager
) : BusinessRealtime {

    private fun businessId(): String =
        (session.state.value as? SessionState.SignedIn)?.business?.id
            ?: throw IllegalStateException("Not signed in")

    private fun ref(path: String): DatabaseReference {
        val clean = path.trim('/')
        val full = if (clean.isEmpty()) "businesses/${businessId()}" else "businesses/${businessId()}/$clean"
        return database.getReference(full)
    }

    private fun message(error: DatabaseError): String =
        if (error.code == DatabaseError.PERMISSION_DENIED) "You don't have access to this data."
        else error.message

    override fun <T : Any> observe(path: String, clazz: Class<T>): Flow<Resource<T?>> = callbackFlow {
        trySend(Resource.Loading)
        var listener: ValueEventListener? = null
        var reference: DatabaseReference? = null
        try {
            reference = ref(path)
            listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        trySend(Resource.Success(snapshot.getValue(clazz)))
                    } catch (e: Exception) {
                        trySend(Resource.Error("Could not read this data.", e))
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    trySend(Resource.Error(message(error), error.toException()))
                }
            }
            reference.addValueEventListener(listener)
        } catch (e: Exception) {
            trySend(Resource.Error(e.message ?: "Something went wrong while loading this data.", e))
        }
        awaitClose {
            val l = listener
            val r = reference
            if (l != null && r != null) r.removeEventListener(l)
        }
    }

    override fun <T : Any> observeMap(path: String, clazz: Class<T>): Flow<Resource<Map<String, T>>> = callbackFlow {
        trySend(Resource.Loading)
        var listener: ValueEventListener? = null
        var reference: DatabaseReference? = null
        try {
            reference = ref(path)
            listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val result = LinkedHashMap<String, T>()
                        snapshot.children.forEach { child ->
                            val key = child.key
                            val value = child.getValue(clazz)
                            if (key != null && value != null) result[key] = value
                        }
                        trySend(Resource.Success<Map<String, T>>(result))
                    } catch (e: Exception) {
                        trySend(Resource.Error("Could not read this data.", e))
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    trySend(Resource.Error(message(error), error.toException()))
                }
            }
            reference.addValueEventListener(listener)
        } catch (e: Exception) {
            trySend(Resource.Error(e.message ?: "Something went wrong while loading this data.", e))
        }
        awaitClose {
            val l = listener
            val r = reference
            if (l != null && r != null) r.removeEventListener(l)
        }
    }

    override suspend fun set(path: String, value: Any?) {
        ref(path).setValue(value).await()
    }

    override suspend fun update(path: String, fields: Map<String, Any?>) {
        ref(path).updateChildren(fields).await()
    }

    override suspend fun increment(path: String, delta: Long) {
        ref(path).setValue(ServerValue.increment(delta)).await()
    }
}
