package com.westly.nbms.shell

import com.westly.nbms.core.feature.ShellNavigator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns links such as "/admin/bookings?x=1" into bare routes and queues them for the shell,
 * which opens them through the route guard. A link sent before the shell is on screen waits in the queue.
 */
@Singleton
class ShellNavigatorImpl @Inject constructor() : ShellNavigator {

    private val queue = Channel<String>(capacity = Channel.BUFFERED)

    /** Bare routes waiting to be opened. Collected by the shell. */
    val requests: Flow<String> = queue.receiveAsFlow()

    override fun open(link: String) {
        queue.trySend(ShellLinks.toRoute(link))
    }
}
