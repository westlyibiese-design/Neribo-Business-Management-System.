package com.westly.nbms.features.restaurant

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Binds the Restaurant feature into the app's feature set, and the real database stores behind the menu and orders repositories. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RestaurantModule {

    @Binds
    @IntoSet
    abstract fun bindRestaurantFeature(impl: RestaurantFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindMenuStore(impl: FirestoreMenuStore): MenuStore

    @Binds
    @Singleton
    abstract fun bindOrdersStore(impl: FirestoreOrdersStore): OrdersStore
}
