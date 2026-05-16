package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolAnnotations
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.DebugSessionInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.xdebugger.attach.LocalAttachHost
import com.intellij.xdebugger.attach.XAttachDebuggerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Attaches the IDE debugger to a running local process by PID.
 */
class AttachDebuggerToProcessTool : AbstractMcpTool() {

    override val name = "attach_debugger_to_process"

    override val description = """
        Attaches the IDE debugger to a running local process by PID.
        Use list_local_processes first to discover available PIDs and debugger types.
        If multiple debugger types are available and debugger_type is omitted, the first available one is used.
    """.trimIndent()

    override val annotations = ToolAnnotations.mutable("Attach Debugger to Process")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (propName, propSchema) = projectPathProperty()
            put(propName, propSchema)
            putJsonObject("pid") {
                put("type", "integer")
                put("description", "PID of the process to attach to. Use list_local_processes to discover PIDs.")
            }
            putJsonObject("debugger_type") {
                put("type", "string")
                put("description", "Debugger type to use (e.g. 'Java', 'Python'). Uses the first available type if omitted.")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("pid"))
        }
        put("additionalProperties", false)
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val pid = arguments["pid"]?.jsonPrimitive?.longOrNull
            ?: return createErrorResult("Missing required parameter: pid")

        val debuggerType = arguments["debugger_type"]?.jsonPrimitive?.content

        val localAttachHost = LocalAttachHost.INSTANCE
        val providers = XAttachDebuggerProvider.EP.extensionList
        val contextHolder = UserDataHolderBase()

        val processes = try {
            localAttachHost.getProcessList()
        } catch (e: Exception) {
            return createErrorResult("Failed to retrieve process list: ${e.message}")
        }

        val processInfo = processes.find { it.pid.toLong() == pid }
            ?: return createErrorResult("No process found with PID: $pid")

        val availableDebuggers = providers.flatMap { provider ->
            try {
                provider.getAvailableDebuggers(project, localAttachHost, processInfo, contextHolder)
            } catch (e: Exception) {
                emptyList()
            }
        }

        if (availableDebuggers.isEmpty()) {
            return createErrorResult(
                "No debugger available for PID $pid (${processInfo.executableDisplayName}). " +
                "Use list_local_processes to check which processes are attachable."
            )
        }

        val debugger = if (debuggerType != null) {
            availableDebuggers.find { it.debuggerDisplayName == debuggerType }
                ?: return createErrorResult(
                    "Debugger type '$debuggerType' not available for PID $pid. " +
                    "Available: ${availableDebuggers.map { it.debuggerDisplayName }.distinct()}"
                )
        } else {
            availableDebuggers.first()
        }

        return try {
            val sessionCountBefore = getDebuggerManager(project).debugSessions.size

            withContext(Dispatchers.Main) {
                debugger.attachDebugSession(project, localAttachHost, processInfo)
            }

            val newSession = withTimeoutOrNull(30000L) {
                while (true) {
                    delay(500)
                    val sessions = getDebuggerManager(project).debugSessions
                    if (sessions.size > sessionCountBefore) {
                        return@withTimeoutOrNull sessions.lastOrNull()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            if (newSession != null) {
                createJsonResult(
                    AttachDebuggerToProcessResult(
                        status = "attached",
                        message = "Debugger attached to process $pid (${processInfo.executableDisplayName}) " +
                                  "using ${debugger.debuggerDisplayName}",
                        session = DebugSessionInfo(
                            id = getSessionId(newSession),
                            name = newSession.sessionName,
                            state = if (newSession.isPaused) "paused" else "running",
                            isCurrent = newSession == getCurrentSession(project),
                            processId = pid
                        )
                    )
                )
            } else {
                createJsonResult(
                    AttachDebuggerToProcessResult(
                        status = "attaching",
                        message = "Attaching debugger to process $pid (${processInfo.executableDisplayName}) — " +
                                  "session may take a moment to initialize",
                        session = null
                    )
                )
            }
        } catch (e: Exception) {
            createErrorResult("Failed to attach debugger to process $pid: ${e.message}")
        }
    }
}

@Serializable
data class AttachDebuggerToProcessResult(
    val status: String,
    val message: String,
    val session: DebugSessionInfo?
)
