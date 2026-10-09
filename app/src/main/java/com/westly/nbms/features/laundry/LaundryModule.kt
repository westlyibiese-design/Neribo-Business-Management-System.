package com.westly.nbms.features.laundry

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Laundry feature (Manage Laundry) into the app's feature set, and nothing else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LaundryModule {

    @Binds
    @IntoSet
    abstract fun bindLaundryFeature(impl: LaundryFeature): NbmsFeature
}
