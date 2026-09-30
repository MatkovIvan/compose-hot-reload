/*
 * Copyright 2024-2026 JetBrains s.r.o. and Compose Hot Reload contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

package org.jetbrains.compose.reload.tests.gradle

import org.jetbrains.compose.reload.core.HOT_RELOAD_VERSION
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.BuildTaskResult
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ClientConnected
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.InvalidatedComposeGroupMessage
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ReloadClassesRequest
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ReloadClassesResult
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.ShutdownRequest
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.TestEvent
import org.jetbrains.compose.reload.test.gradle.BuildGradleKtsExtension
import org.jetbrains.compose.reload.test.gradle.BuildMode
import org.jetbrains.compose.reload.test.gradle.HotReloadTest
import org.jetbrains.compose.reload.test.gradle.HotReloadTestFixture
import org.jetbrains.compose.reload.test.gradle.OverrideBuildGradleKts
import org.jetbrains.compose.reload.test.gradle.ProjectMode
import org.jetbrains.compose.reload.test.gradle.TestedBuildMode
import org.jetbrains.compose.reload.test.gradle.TestedProjectMode
import org.jetbrains.compose.reload.test.gradle.TransactionScope
import org.jetbrains.compose.reload.test.gradle.WithGradleProperty
import org.jetbrains.compose.reload.test.gradle.getDefaultMainKtSourceFile
import org.jetbrains.compose.reload.test.gradle.launchApplication
import org.jetbrains.compose.reload.test.gradle.testedComposeVersion
import org.jetbrains.compose.reload.utils.GradleIntegrationTest
import org.jetbrains.compose.reload.utils.HostIntegrationTest
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs an application, which renders the Compose runtime with its own applier on its own thread,
 * without Compose Multiplatform.
 */
@TestedProjectMode(ProjectMode.Jvm)
@OverrideBuildGradleKts(RuntimeOnlyTest.BuildGradleKts::class)
class RuntimeOnlyTest {

    @HotReloadTest
    @HostIntegrationTest
    @GradleIntegrationTest
    @TestedBuildMode(BuildMode.Continuous)
    fun `test - reload`(fixture: HotReloadTestFixture) = fixture.runTest {
        val application = launchApplicationAndAwait("A:0|widget0")

        val descendants = application.descendants().toList()
        assertTrue(descendants.isNotEmpty(), "Expected the supervisor process to be running")

        runTransaction {
            TestEvent("increment").send()
            awaitOutput("A:1|widget0")
        }

        /* Changed code: The state is retained, the changed group is invalidated */
        runTransaction {
            replaceSourceCode("\"A:", "\"B:")
            awaitReloadResult()
            skipToMessage<InvalidatedComposeGroupMessage> { message ->
                message.dirtyScopes.any { scope -> scope.methodName == "Content" }
            }
            awaitOutput("B:1|widget0")
        }

        /* Changed class structure: A method is added to the class of a remembered instance */
        runTransaction {
            fixture.updateMainKt { source ->
                source.replace("class Counter {", "class Counter {\n    fun label() = \"n=\$count\"")
                    .replace("\"B:\${counter.count}\"", "\"B:\${counter.label()}\"")
            }
            awaitReloadResult()
            awaitOutput("B:n=1|widget0")
        }

        /* Added class */
        runTransaction {
            fixture.updateMainKt { source ->
                source.replace("Text(\"B:", "Text(Added().text())\n    Text(\"B:")
                    .plus("\nclass Added { fun text() = \"added\" }\n")
            }
            awaitReloadResult()
            awaitOutput("added|B:n=1|widget0")
        }

        /* Changed code in a project dependency */
        runTransaction {
            replaceSourceCode("widgets/src/main/kotlin/Widgets.kt", "widget0", "widget1")
            awaitReloadResult()
            awaitOutput("added|B:n=1|widget1")
        }

        /* Compilation failure: The application keeps running and recovers with the next successful compilation */
        runTransaction {
            fixture.updateMainKt { source -> "$source\nfun broken( =\n" }
            skipToMessage<BuildTaskResult> { result -> !result.isSuccess }
            assertTrue(application.isAlive)
            TestEvent("increment").send()
            awaitOutput("added|B:n=2|widget1")
        }

        runTransaction {
            fixture.updateMainKt { source -> source.replace("\nfun broken( =\n", "").replace("\"B:", "\"C:") }
            awaitReloadResult()
            awaitOutput("added|C:n=2|widget1")
        }

        runTransaction {
            ShutdownRequest("Requested by the test").send()
            application.onExit().get(30, TimeUnit.SECONDS)
            descendants.forEach { process -> process.onExit().get(30, TimeUnit.SECONDS) }
        }
    }

    /* Another project of the build applies the Compose Multiplatform plugin */
    @HotReloadTest
    @HostIntegrationTest
    @GradleIntegrationTest
    @TestedBuildMode(BuildMode.Continuous)
    @OverrideBuildGradleKts(RuntimeOnlyTest.BuildGradleKtsWithComposePluginInWidgets::class)
    fun `test - supervisor is stopped - application killed`(fixture: HotReloadTestFixture) = fixture.runTest {
        val application = launchApplicationAndAwait("A:0|widget0")
        val descendants = application.descendants().toList()
        assertTrue(descendants.isNotEmpty(), "Expected the supervisor process to be running")

        application.destroyForcibly()
        descendants.forEach { process -> process.onExit().get(30, TimeUnit.SECONDS) }
    }

    @HotReloadTest
    @HostIntegrationTest
    @GradleIntegrationTest
    @WithGradleProperty("compose.reload.devToolsEnabled", "false")
    fun `test - reload - supervisor disabled`(fixture: HotReloadTestFixture) = fixture.runTest {
        val application = launchApplicationAndAwait("A:0|widget0", isSupervisorEnabled = false)
        assertEquals(emptyList(), application.descendants().toList())

        runTransaction {
            replaceSourceCode("\"A:", "\"B:")
            requestReload()
            awaitOutput("B:0|widget0")
        }
    }

    /**
     * @return The process of the launched application, once it rendered the [expectedOutput] and the
     * supervisor finished its first build.
     */
    private suspend fun HotReloadTestFixture.launchApplicationAndAwait(
        expectedOutput: String, isSupervisorEnabled: Boolean = true
    ): ProcessHandle {
        projectDir.settingsGradleKts.appendText("\ninclude(\":widgets\")\n")
        return runTransaction {
            writeCode("widgets/src/main/kotlin/Widgets.kt", widgetsKt)
            writeCode("src/main/kotlin/Renderer.kt", rendererKt)
            writeCode(source = mainKt)

            val firstBuild = launchChildTransaction {
                if (!isSupervisorEnabled) return@launchChildTransaction
                skipToMessage<ReloadClassesRequest> { request -> request.changedClassFiles.isEmpty() }
                skipToMessage<ReloadClassesResult>()
            }

            this@launchApplicationAndAwait.launchApplication()
            val application = skipToMessage<ClientConnected> { it.clientRole == OrchestrationClientRole.Application }
            assertNoRenderingDependency(awaitPayload("classpath:"))
            if (isSupervisorEnabled) assertNoRenderingDependency(awaitPayload("supervisor-classpath:"))
            awaitOutput(expectedOutput)
            firstBuild.join()
            ProcessHandle.of(assertNotNull(application.clientPid)).get()
        }
    }

    private fun HotReloadTestFixture.updateMainKt(update: (source: String) -> String) {
        val mainKt = projectDir.resolve(getDefaultMainKtSourceFile())
        mainKt.writeText(update(mainKt.readText()))
    }

    private suspend fun TransactionScope.awaitPayload(prefix: String): String {
        return skipToMessage<TestEvent> { "${it.payload}".startsWith(prefix) }.payload.toString().removePrefix(prefix)
    }

    private suspend fun TransactionScope.awaitOutput(output: String) {
        skipToMessage<TestEvent>("Waiting for output '$output'") { it.payload == "output:$output" }
    }

    private suspend fun TransactionScope.awaitReloadResult() {
        val request = skipToMessage<ReloadClassesRequest> { request -> request.changedClassFiles.isNotEmpty() }
        val result = skipToMessage<ReloadClassesResult> { result -> result.reloadRequestId == request.messageId }
        if (!result.isSuccess) fail("Reload failed: ${result.errorMessage}")
    }

    private fun assertNoRenderingDependency(classpath: String) {
        val files = classpath.split(File.pathSeparator).map { File(it).name }
        assertTrue(files.any { it.startsWith("hot-reload-") }, "Unexpected classpath: $files")
        val renderingDependency = Regex(
            "^(ui|foundation|material|animation|desktop|skiko|hot-reload-(runtime-jvm|devtools-\\d))"
        )
        assertEquals(emptyList(), files.filter { renderingDependency.containsMatchIn(it) })
    }

    open class BuildGradleKts(private val isComposePluginAppliedInWidgets: Boolean = false) : BuildGradleKtsExtension {
        override fun plugins(context: ExtensionContext): String = """
            kotlin("jvm")
            kotlin("plugin.compose")
            id("org.jetbrains.compose.hot-reload")
            ${if (isComposePluginAppliedInWidgets) "id(\"org.jetbrains.compose\") apply false" else ""}
        """.trimIndent()

        override fun jvmMainDependencies(context: ExtensionContext): String = """
            implementation("org.jetbrains.compose.runtime:runtime:${context.testedComposeVersion}")
            implementation("org.jetbrains.compose.hot-reload:hot-reload-runtime-api:$HOT_RELOAD_VERSION")
            compileOnly("org.jetbrains.compose.hot-reload:hot-reload-agent:$HOT_RELOAD_VERSION")
            compileOnly("org.jetbrains.compose.hot-reload:hot-reload-core:$HOT_RELOAD_VERSION")
            compileOnly("org.jetbrains.compose.hot-reload:hot-reload-orchestration:$HOT_RELOAD_VERSION")
            implementation(project(":widgets"))
        """.trimIndent()

        override fun footer(context: ExtensionContext): String = """
            project(":widgets") {
                apply(plugin = "org.jetbrains.kotlin.jvm")
                apply(plugin = "org.jetbrains.kotlin.plugin.compose")
                ${if (isComposePluginAppliedInWidgets) "apply(plugin = \"org.jetbrains.compose\")" else ""}
                dependencies {
                    "implementation"("org.jetbrains.compose.runtime:runtime:${context.testedComposeVersion}")
                }
            }
        """.trimIndent()
    }

    class BuildGradleKtsWithComposePluginInWidgets : BuildGradleKts(isComposePluginAppliedInWidgets = true)
}

private const val widgetsKt = """
import androidx.compose.runtime.Composable

@Composable
fun Widget(content: @Composable (text: String) -> Unit) {
    content("widget0")
}
"""

private const val mainKt = """
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.filterIsInstance
import org.jetbrains.compose.reload.agent.orchestration
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.TestEvent
import org.jetbrains.compose.reload.orchestration.asFlow

class Counter {
    var count by mutableIntStateOf(0)
}

@Composable
fun App() {
    val counter = remember { Counter() }
    LaunchedEffect(Unit) {
        orchestration.asFlow().filterIsInstance<TestEvent>().collect { event ->
            if (event.payload == "increment") counter.count++
        }
    }
    Content(counter)
}

@Composable
fun Content(counter: Counter) {
    Text("A:${'$'}{counter.count}")
    Widget { text -> Text(text) }
}

fun main() {
    render { App() }
}
"""

/**
 * A renderer which is not based on Compose UI or Swing:
 * It composes on the main thread and sends the text of its nodes as [TestEvent].
 */
private const val rendererKt = """
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.reload.DelicateHotReloadApi
import org.jetbrains.compose.reload.DevelopmentEntryPoint
import org.jetbrains.compose.reload.agent.sendAsync
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage.TestEvent

class TextNode {
    var text = ""
    val children = mutableListOf<TextNode>()
}

class TextApplier(root: TextNode) : AbstractApplier<TextNode>(root) {
    override fun insertTopDown(index: Int, instance: TextNode) = Unit
    override fun insertBottomUp(index: Int, instance: TextNode) = current.children.add(index, instance)
    override fun remove(index: Int, count: Int) = current.children.remove(index, count)
    override fun move(from: Int, to: Int, count: Int) = current.children.move(from, to, count)
    override fun onClear() = root.children.clear()
    override fun onEndChanges() {
        TestEvent("output:" + root.children.joinToString("|") { it.text }).sendAsync()
    }
}

@Composable
fun Text(text: String) {
    ComposeNode<TextNode, TextApplier>(::TextNode) {
        set(text) { this.text = it }
    }
}

@OptIn(DelicateHotReloadApi::class)
fun render(content: @Composable () -> Unit): Unit = runBlocking {
    TestEvent("classpath:" + System.getProperty("java.class.path")).sendAsync()
    TestEvent("supervisor-classpath:" + System.getProperty("compose.reload.devToolsClasspath")).sendAsync()

    val frameClock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + frameClock)
    launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
    launch {
        while (true) {
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(System.nanoTime())
            delay(16)
        }
    }

    Composition(TextApplier(TextNode()), recomposer).setContent {
        DevelopmentEntryPoint { content() }
    }
}
"""
