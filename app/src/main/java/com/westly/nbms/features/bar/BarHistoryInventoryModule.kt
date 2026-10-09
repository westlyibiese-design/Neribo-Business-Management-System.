package com.westly.nbms.features.bar

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Sales History and Bar Inventory feature into the app's feature set, and nothing else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BarHistoryInventoryModule {

    @Binds
    @IntoSet
    abstract fun bindBarHistoryInventoryFeature(impl: BarHistoryInventoryFeature): NbmsFeature
}
