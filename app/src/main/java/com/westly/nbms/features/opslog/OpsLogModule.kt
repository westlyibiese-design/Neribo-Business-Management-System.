package com.westly.nbms.features.opslog

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class OpsLogModule {
    @Binds
    @IntoSet
    abstract fun bind(f: OpsLogFeature): NbmsFeature
}
