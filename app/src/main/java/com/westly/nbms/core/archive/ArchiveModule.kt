package com.westly.nbms.core.archive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveModule {

    @Binds
    @Singleton
    abstract fun bindRecordArchiver(impl: RecordArchiverImpl): RecordArchiver

    /** Declared here so the set exists (empty) until a later phase adds listeners with @IntoSet. */
    @Multibinds
    abstract fun softDeleteListeners(): Set<SoftDeleteListener>
}
