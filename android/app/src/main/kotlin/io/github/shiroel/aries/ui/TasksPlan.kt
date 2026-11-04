package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.ChatEntry
import io.github.shiroel.aries.core.ChatEntryBody
import io.github.shiroel.aries.core.ChatToolStatus
import org.json.JSONObject

internal enum class TaskPlanStatus { PENDING, IN_PROGRESS, COMPLETED }
internal data class TaskPlanItem(val text: String, val status: TaskPlanStatus)

/** The Beacon already preserves tool inputs; no transcript IO or Agent-specific RPC is needed. */
internal fun claudeTaskPlan(entries: List<ChatEntry>): List<TaskPlanItem> =
    latestPlan(entries, "TodoWrite", "todos", "content").orEmpty()
internal fun codexTaskPlan(entries: List<ChatEntry>): List<TaskPlanItem> =
    latestPlan(entries, "update_plan", "plan", "step").orEmpty()

internal fun taskPlan(entries: List<ChatEntry>): List<TaskPlanItem> = entries.asReversed().firstNotNullOfOrNull { entry ->
    when ((entry.body as? ChatEntryBody.Tool)?.name?.substringAfterLast('.')) {
        "TodoWrite" -> latestPlan(listOf(entry), "TodoWrite", "todos", "content")
        "update_plan" -> latestPlan(listOf(entry), "update_plan", "plan", "step")
        else -> null
    }
}.orEmpty()

private fun latestPlan(entries: List<ChatEntry>, name: String, array: String, text: String): List<TaskPlanItem>? {
    for (entry in entries.asReversed()) {
        val tool = entry.body as? ChatEntryBody.Tool ?: continue
        if (tool.name.substringAfterLast('.') != name || tool.status == ChatToolStatus.FAILED) continue
        val input = runCatching { JSONObject(tool.input) }.getOrNull() ?: continue
        val steps = input.optJSONArray(array) ?: continue
        val parsed = mutableListOf<TaskPlanItem>()
        var valid = true
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i)
            val label = step?.optString(text)?.takeIf { it.isNotBlank() }
            val status = when (step?.optString("status")) {
                "pending" -> TaskPlanStatus.PENDING
                "in_progress" -> TaskPlanStatus.IN_PROGRESS
                "completed" -> TaskPlanStatus.COMPLETED
                else -> null
            }
            if (label == null || status == null) { valid = false; break }
            parsed += TaskPlanItem(label, status)
        }
        if (valid) return parsed
    }
    return null
}
