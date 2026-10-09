package com.westly.nbms.features.inventory

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Binds the Inventory feature into the app's feature set, and the real database store behind the repository. */
@Module
@InstallIn(SingletonComponent::class)
abstract class InventoryModule {

    @Binds
    @IntoSet
    abstract fun bindInventoryFeature(impl: InventoryFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindInventoryStore(impl: FirestoreInventoryStore): InventoryStore
}
