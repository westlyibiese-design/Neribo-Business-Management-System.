package com.westly.nbms.features.shifts

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ShiftsModule {
    @Binds
    @IntoSet
    abstract fun bind(f: ShiftsFeature): NbmsFeature
}
