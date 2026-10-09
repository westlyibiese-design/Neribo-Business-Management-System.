package com.westly.nbms.features.finance

import com.westly.nbms.core.feature.NbmsFeature
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Binds the Payments / Approvals feature. It does not bind the revenue ledger (Part 16A does that). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PaymentsApprovalsModule {

    @Binds
    @IntoSet
    abstract fun bindPaymentsApprovalsFeature(impl: PaymentsApprovalsFeature): NbmsFeature
}
