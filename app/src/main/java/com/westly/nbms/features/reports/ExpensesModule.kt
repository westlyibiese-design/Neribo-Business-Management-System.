package com.westly.nbms.features.reports

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Expenses feature into the app's feature set. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExpensesModule {

    @Binds
    @IntoSet
    abstract fun bindExpensesFeature(impl: ExpensesFeature): NbmsFeature
}
