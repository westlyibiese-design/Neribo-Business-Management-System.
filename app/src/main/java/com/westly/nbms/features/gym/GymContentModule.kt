package com.westly.nbms.features.gym

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class GymContentModule {
    @Binds
    @IntoSet
    abstract fun bind(f: GymContentFeature): NbmsFeature
}
