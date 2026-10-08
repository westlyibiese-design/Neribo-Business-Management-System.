package com.westly.nbms.shell

import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.feature.TopBarAction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {

    @Binds
    abstract fun bindShellNavigator(impl: ShellNavigatorImpl): ShellNavigator

    /** Declares the top bar action set so it compiles (empty) before Phase 10 adds the bell. */
    @Multibinds
    abstract fun topBarActions(): Set<TopBarAction>
}
