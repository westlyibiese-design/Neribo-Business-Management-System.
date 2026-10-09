package com.westly.nbms.features.bar

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Bar feature (New Sale and Drinks Menu) into the app's feature set, and nothing else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BarModule {

    @Binds
    @IntoSet
    abstract fun bindBarFeature(impl: BarFeature): NbmsFeature
}
