package com.westly.nbms.features.settings

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @IntoSet
    abstract fun bindSettingsFeature(impl: SettingsFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindBusinessProfileApi(impl: BusinessProfileApiImpl): BusinessProfileApi
}
