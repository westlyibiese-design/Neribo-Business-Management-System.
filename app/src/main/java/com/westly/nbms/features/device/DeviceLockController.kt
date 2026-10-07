package com.westly.nbms.features.device

import kotlinx.coroutines.flow.StateFlow

/** Appendix A.6.3. `lockNow()` does nothing when this person has no Device PIN on this phone. */
interface DeviceLockController {
    val locked: StateFlow<Boolean>
    fun lockNow()
}

