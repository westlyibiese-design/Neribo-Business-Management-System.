package com.westly.nbms.core.audit

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuditModule {

    @Binds
    @Singleton
    abstract fun bindAuditLogger(impl: AuditLoggerImpl): AuditLogger
}
