/*
 * Copyright 2024-2026 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

@file:JvmName("Main")

package org.jetbrains.compose.reload.supervisor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.reload.core.Disposable
import org.jetbrains.compose.reload.core.HotReloadEnvironment
import org.jetbrains.compose.reload.core.HotReloadProperty
import org.jetbrains.compose.reload.core.createLogger
import org.jetbrains.compose.reload.core.error
import org.jetbrains.compose.reload.core.exception
import org.jetbrains.compose.reload.core.getBlocking
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.core.invokeOnCompletion
import org.jetbrains.compose.reload.core.leftOr
import org.jetbrains.compose.reload.orchestration.OrchestrationClient
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationHandle
import org.jetbrains.compose.reload.orchestration.connectAllOrchestrationListeners
import org.jetbrains.compose.reload.orchestration.connectBlocking
import org.jetbrains.compose.reload.orchestration.startOrchestrationServer
import kotlin.system.exitProcess

private val logger = createLogger()

/**
 * Supervises an application without showing any UI:
 * Hosts the orchestration server, recompiles the application's code and restarts the application on request.
 */
fun main() {
    val orchestration: OrchestrationHandle = HotReloadEnvironment.orchestrationPort?.let { port ->
        OrchestrationClient(OrchestrationClientRole.Tooling, port).connectBlocking().leftOr { error ->
            logger.error("Failed connecting 'orchestration'", error.exception)
            exitProcess(1)
        }
    } ?: startOrchestrationServer().also { server -> server.connectAllOrchestrationListeners() }

    /* Communicate the orchestration port back to the parent process */
    val orchestrationPort = orchestration.port.getBlocking().getOrThrow()
    println(HotReloadProperty.OrchestrationPort.key + "=" + orchestrationPort)
    System.setProperty(HotReloadProperty.OrchestrationPort.key, orchestrationPort.toString())

    orchestration.startLoggingDispatch()
    orchestration.invokeOnCompletion { exitProcess(0) }
    bindParentProcess(orchestration) { exitProcess(0) }

    launchRecompiler(orchestration, ::invokeOnShutdown)
    CoroutineScope(Dispatchers.Default).launchRestartActor(orchestration)
}

private fun invokeOnShutdown(action: () -> Unit): Disposable {
    val shutdownHook = Thread(action)
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    return Disposable {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            /* The shutdown is in progress: The hook will run */
        }
    }
}
