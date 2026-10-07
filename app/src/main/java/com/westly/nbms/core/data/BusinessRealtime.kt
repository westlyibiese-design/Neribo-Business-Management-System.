package com.westly.nbms.core.data

import kotlinx.coroutines.flow.Flow

/** Realtime Database access under businesses/{bid}/<path>. */
interface BusinessRealtime {
    fun <T : Any> observe(path: String, clazz: Class<T>): Flow<Resource<T?>>
    fun <T : Any> observeMap(path: String, clazz: Class<T>): Flow<Resource<Map<String, T>>>
    suspend fun set(path: String, value: Any?)
    suspend fun update(path: String, fields: Map<String, Any?>)
    suspend fun increment(path: String, delta: Long)
}
