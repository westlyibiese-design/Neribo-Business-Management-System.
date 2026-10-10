package com.westly.nbms.features.gym

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Binds the Gym feature into the app's feature set, and the real database stores behind the repositories. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GymModule {

    @Binds
    @IntoSet
    abstract fun bindGymFeature(impl: GymFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindGymMembersStore(impl: FirestoreGymMembersStore): GymMembersStore

    @Binds
    @Singleton
    abstract fun bindGymVisitsStore(impl: FirestoreGymVisitsStore): GymVisitsStore
}
