/*
 * Copyright 2024-2025 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.devtools

import org.jetbrains.compose.reload.core.awaitIdle
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.invokeOnCompletion
import org.jetbrains.compose.reload.core.launchTask
import org.jetbrains.compose.reload.core.reloadMainThread
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.supervisor.bindParentProcess

private val logger = createLogger()

internal fun setupDevToolsProcess() {
    setupExceptionHandler()
    setupShutdownProcedure()
    setupOrchestration()
    bindParentProcess(orchestration, ::shutdown)
}

private fun setupExceptionHandler() {
    Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
        logger.error("Uncaught exception in thread '${thread.name}': ${exception.message}", exception)
        launchTask {
            logger.error("Initiating shutdown because of uncaught exception: ${exception.javaClass.simpleName}")
            OrchestrationMessage.CriticalException(OrchestrationClientRole.Tooling, exception).sendAsync()
            reloadMainThread.awaitIdle()
            shutdown()
        }
    }
}

private fun setupOrchestration() {
    orchestration.invokeOnCompletion {
        shutdown()
    }
}
