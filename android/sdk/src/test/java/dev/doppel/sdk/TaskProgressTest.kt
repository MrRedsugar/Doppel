package dev.doppel.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskProgressTest {
    private fun run() = JSONObject().put("status", "running").put("goal", "找到商品")
    private fun progress(completed: Int = 0, known: Boolean = true) = JSONObject()
        .put("plan", JSONArray(listOf("打开应用", "找到目标", "完成操作")))
        .put("completed", completed).put("total_known", known)

    @Test fun initialNullUpdateAndRestartPreserveCoarsePlan() {
        val run = run(); TaskProgress.initial(run)
        assertFalse(TaskProgress.presentation(run).known)
        TaskProgress.merge(run, progress(1)); TaskProgress.merge(run, null); TaskProgress.initial(run)
        val restored = TaskProgress.presentation(JSONObject(run.toString()))
        assertEquals("已完成 1/3 阶段", restored.label)
        assertEquals("找到目标", restored.current)
        assertEquals(1, restored.completed)
    }

    @Test fun routeChangesAndUnknownRangeDoNotForceMonotonicProgress() {
        val run = run(); TaskProgress.merge(run, progress(2))
        TaskProgress.merge(run, progress(0, false).put("plan", JSONArray(listOf("确认可用路线"))))
        val view = TaskProgress.presentation(run)
        assertFalse(view.known); assertEquals(0, view.completed)
        assertEquals("确认可用路线", view.current); assertEquals("进度待确定", view.label)
    }

    @Test fun onlyVerifiedRunCompletionShowsFullKnownProgress() {
        val run = run(); TaskProgress.merge(run, progress(3))
        assertEquals(2, TaskProgress.presentation(run).completed)
        assertEquals("正在确认任务结果", TaskProgress.presentation(run).current)
        run.put("status", "failed")
        assertFalse(TaskProgress.presentation(run).running)
        assertEquals(2, TaskProgress.presentation(run).completed)
        run.put("status", "completed"); TaskProgress.complete(run)
        assertEquals("任务已完成 · 3/3 阶段", TaskProgress.presentation(run).label)
        assertEquals("任务已完成", TaskProgress.presentation(run).current)
    }

    @Test fun unknownAndOldRunsDoNotInventTotals() {
        val run = run(); TaskProgress.merge(run, progress(3, false))
        assertEquals("正在确定后续阶段", TaskProgress.presentation(run).current)
        assertFalse(TaskProgress.presentation(run).known)
        assertFalse(TaskProgress.presentation(run()).known)
        assertFalse(TaskProgress.presentation(null).running)
        run.put("status", "paused")
        assertFalse(TaskProgress.presentation(run).running)
    }

    @Test fun invalidUpdateCannotDestroyLastSnapshot() {
        val run = run(); TaskProgress.merge(run, progress(1))
        for (invalid in listOf(progress(4), progress(-1), progress().put("completed", 1.5),
            progress().put("completed", "1"), progress().put("total_known", "true"),
            progress().put("plan", JSONArray()), progress().put("plan", JSONArray(listOf(" "))),
            progress().put("plan", JSONArray(List(6) { "阶段$it" })), progress().put("extra", true))) {
            assertThrows(IllegalArgumentException::class.java) { TaskProgress.merge(run, invalid) }
            assertEquals(1, TaskProgress.presentation(run).completed)
        }
        run.getJSONObject("task_state").put("progress", JSONObject().put("broken", true))
        assertFalse(TaskProgress.presentation(run).known)
    }
}
