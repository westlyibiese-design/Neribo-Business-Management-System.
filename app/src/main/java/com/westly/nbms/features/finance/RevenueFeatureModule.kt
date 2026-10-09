package com.westly.nbms.features.finance

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Revenue feature and nothing else. (The ledger is bound by FinanceModule from Part 16A-1.) */
@Module
@InstallIn(SingletonComponent::class)
abstract class RevenueFeatureModule {

    @Binds
    @IntoSet
    abstract fun bindRevenueFeature(impl: RevenueFeature): NbmsFeature
}
