package com.westly.nbms.features.finance

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the revenue ledger and nothing else. (The feature binding of the Revenue page belongs to Part 16A-2.) */
@Module
@InstallIn(SingletonComponent::class)
abstract class FinanceModule {

    @Binds
    @Singleton
    abstract fun bindRevenueLedger(impl: RevenueLedgerImpl): RevenueLedger
}
