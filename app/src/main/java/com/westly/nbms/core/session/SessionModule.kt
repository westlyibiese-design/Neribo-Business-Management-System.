package com.westly.nbms.core.session

import com.westly.nbms.core.data.BusinessFirestore
import com.westly.nbms.core.data.BusinessFirestoreImpl
import com.westly.nbms.core.data.BusinessRealtime
import com.westly.nbms.core.data.BusinessRealtimeImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt bindings for everything in core/session and core/data. (Clients are provided in core/data/Clients.kt.) */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionManager(impl: SessionManagerImpl): SessionManager

    @Binds
    @Singleton
    abstract fun bindBusinessFirestore(impl: BusinessFirestoreImpl): BusinessFirestore

    @Binds
    @Singleton
    abstract fun bindBusinessRealtime(impl: BusinessRealtimeImpl): BusinessRealtime
}
