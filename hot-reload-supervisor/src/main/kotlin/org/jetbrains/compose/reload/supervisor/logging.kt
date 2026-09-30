/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.supervisor

import org.jetbrains.compose.reload.InternalHotReloadApi
import org.jetbrains.compose.reload.core.Logger
import org.jetbrains.compose.reload.core.Queue
import org.jetbrains.compose.reload.core.await
import org.jetbrains.compose.reload.core.isTaskActive
import org.jetbrains.compose.reload.orchestration.OrchestrationHandle
import org.jetbrains.compose.reload.orchestration.OrchestrationLoggerState
import org.jetbrains.compose.reload.orchestration.toMessage

@InternalHotReloadApi
val supervisorLoggingQueue = Queue<Logger.Log>()

internal class SupervisorLoggerDispatch : Logger.Dispatch {
    override fun add(log: Logger.Log) {
        supervisorLoggingQueue.add(log)
    }
}

@InternalHotReloadApi
fun OrchestrationHandle.startLoggingDispatch() = subtask {
    val loggingState = states.get(OrchestrationLoggerState)

    /* Await at lest one logger to be present in the orchestration, to avoid logs being swallowed */
    if (loggingState.value.loggers.isEmpty()) {
        loggingState.await { state -> state.loggers.isNotEmpty() }
    }

    while (isTaskActive) {
        val log = supervisorLoggingQueue.receive()
        try {
            send(log.toMessage())
        } catch (t: Throwable) {
            /* Re-Dispatch log */
            supervisorLoggingQueue.send(log)
            throw t
        }
    }
}
