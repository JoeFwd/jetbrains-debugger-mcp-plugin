package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Lists local running processes that the IDE debugger can attach to.
 */
class ListLocalProcessesTool : AbstractMcpTool() {

    override val name = "list_local_processes"

    override val description = """
        Lists local running processes that the IDE debugger can attach to.
        Returns process IDs, names, command lines, and available debugger types for each attachable process.
        Use before attach_debugger_to_process to discover the PID and available debugger types.
    """.trimIndent()

    override val annotations = ToolAnnotations.readOnly("List Local Processes")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val localAttachHost = LocalAttachHost.INSTANCE
        val providers = XAttachDebuggerProvider.EP.extensionList
        val contextHolder = UserDataHolderBase()

        val processes = try {
            localAttachHost.getProcessList()
        } catch (e: Exception) {
            return createErrorResult("Failed to retrieve process list: ${e.message}")
        }

        val attachableProcesses = processes.mapNotNull { processInfo ->
            val debuggers = providers.flatMap { provider ->
                try {
                    provider.getAvailableDebuggers(project, localAttachHost, processInfo, contextHolder)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (debuggers.isEmpty()) null
            else AttachableProcessInfo(
                pid = processInfo.pid.toLong(),
                executableName = processInfo.executableDisplayName,
                commandLine = processInfo.commandLine,
                availableDebuggers = debuggers.map { it.debuggerDisplayName }.distinct()
            )
        }

        return createJsonResult(
            ListLocalProcessesResult(
                processes = attachableProcesses,
                totalCount = attachableProcesses.size
            )
        )
    }
}

@Serializable
data class ListLocalProcessesResult(
    val processes: List<AttachableProcessInfo>,
    val totalCount: Int
)

@Serializable
data class AttachableProcessInfo(
    val pid: Long,
    val executableName: String,
    val commandLine: String,
    val availableDebuggers: List<String>
)
