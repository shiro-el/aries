package io.github.shiroel.aries.ui

import io.github.shiroel.aries.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression: Agent tool format changes must not silently drop or misreport plan completion. */
@RunWith(RobolectricTestRunner::class)
class TasksPlanTest {
    private fun tool(name: String, input: String, status: ChatToolStatus = ChatToolStatus.DONE) =
        ChatEntry("plan", null, ChatEntryBody.Tool(name, "", input, status, null))

    @Test fun claudeTodoWriteUsesTheLatestChecklistIncludingAnExplicitClear() {
        val first = tool("TodoWrite", """{"todos":[{"content":"Inspect source","status":"completed"},{"content":"Run checks","status":"in_progress"},{"content":"Review patch","status":"pending"}]}""")
        assertEquals(listOf(TaskPlanItem("Inspect source", TaskPlanStatus.COMPLETED),
            TaskPlanItem("Run checks", TaskPlanStatus.IN_PROGRESS), TaskPlanItem("Review patch", TaskPlanStatus.PENDING)), claudeTaskPlan(listOf(first)))
        assertEquals(emptyList<TaskPlanItem>(), claudeTaskPlan(listOf(first, tool("TodoWrite", """{"todos":[]}"""))))
    }

    @Test fun codexUpdatePlanUsesRecordedStepsAndCompletionWithoutGuessingFromProse() {
        val first = tool("functions.update_plan", """{"explanation":"Ready to verify","plan":[{"step":"Inspect source","status":"completed"},{"step":"Run checks","status":"in_progress"},{"step":"Review patch","status":"pending"}]}""")
        assertEquals(listOf(TaskPlanItem("Inspect source", TaskPlanStatus.COMPLETED),
            TaskPlanItem("Run checks", TaskPlanStatus.IN_PROGRESS), TaskPlanItem("Review patch", TaskPlanStatus.PENDING)), taskPlan(listOf(first)))
        assertEquals(listOf(TaskPlanItem("Run checks", TaskPlanStatus.COMPLETED)),
            taskPlan(listOf(first, tool("update_plan", """{"plan":[{"step":"Run checks","status":"completed"}]}"""))))
        assertEquals(taskPlan(listOf(first)), codexTaskPlan(listOf(first)))
    }

    @Test fun absentFailedAndMalformedPlansNeverInventChecklistItems() {
        val prose = ChatEntry("agent", null, ChatEntryBody.Agent("Plan: [x] inspect source"))
        assertTrue(taskPlan(emptyList()).isEmpty())
        assertTrue(claudeTaskPlan(listOf(prose)).isEmpty())
        assertTrue(codexTaskPlan(listOf(prose)).isEmpty())
        assertTrue(taskPlan(listOf(tool("Bash", """{"plan":[{"step":"Not a plan","status":"completed"}]}"""))).isEmpty())
        val valid = tool("update_plan", """{"plan":[{"step":"Inspect source","status":"completed"}]}""")
        val expected = listOf(TaskPlanItem("Inspect source", TaskPlanStatus.COMPLETED))
        assertEquals(expected, taskPlan(listOf(valid, tool("update_plan", "{", ChatToolStatus.RUNNING))))
        assertEquals(expected, taskPlan(listOf(valid, tool("update_plan", """{"plan":[{"step":"Invalid","status":"unknown"}]}"""))))
        assertEquals(expected, taskPlan(listOf(valid, tool("update_plan", """{"plan":[]}""", ChatToolStatus.FAILED))))
        assertTrue(taskPlan(listOf(valid, tool("update_plan", """{"plan":[]}"""))).isEmpty())
    }
}
