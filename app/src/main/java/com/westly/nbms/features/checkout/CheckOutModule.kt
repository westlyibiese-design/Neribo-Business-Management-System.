package com.westly.nbms.features.checkout

import com.westly.nbms.core.feature.NbmsFeature
import com.westly.nbms.features.bookings.ExtendStayLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CheckOutModule {

    @Binds
    @IntoSet
    abstract fun bindCheckOutFeature(impl: CheckOutFeature): NbmsFeature

    /** Fills the (until now empty) launcher set that Phase 12's Bookings page reads. */
    @Binds
    @IntoSet
    abstract fun bindExtendStayLauncher(impl: ExtendStayLauncherImpl): ExtendStayLauncher

    @Binds
    @Singleton
    abstract fun bindCheckOutStore(impl: FirestoreCheckOutStore): CheckOutStore

    @Binds
    @Singleton
    abstract fun bindExtendStayStore(impl: FirestoreExtendStayStore): ExtendStayStore

    @Binds
    @Singleton
    abstract fun bindCheckOutNetwork(impl: AndroidCheckOutNetwork): CheckOutNetwork
}
