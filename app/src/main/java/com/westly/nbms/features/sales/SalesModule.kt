package com.westly.nbms.features.sales

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SalesModule {

    @Binds
    @IntoSet
    abstract fun bindSalesFeature(impl: SalesFeature): NbmsFeature

    @Binds
    @Singleton
    abstract fun bindSalesStore(impl: FirestoreSalesStore): SalesStore
}
