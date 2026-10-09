package com.westly.nbms.features.rooms

import com.westly.nbms.core.feature.ImageFieldProvider
import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RoomsModule {

    @Binds
    @Singleton
    abstract fun bindRoomLogic(impl: RoomLogicImpl): RoomLogic

    @Binds
    @Singleton
    abstract fun bindRoomLogicStore(impl: FirestoreRoomLogicStore): RoomLogicStore

    @Binds
    @IntoSet
    abstract fun bindRoomsFeature(impl: RoomsFeature): NbmsFeature

    /** The image-upload providers (Phase 30 adds one with @IntoSet). Declared here so the set exists, empty, until then. */
    @Multibinds
    abstract fun imageFieldProviders(): Set<ImageFieldProvider>
}
