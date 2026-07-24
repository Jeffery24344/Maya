package com.jeffery.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GoalStep(val text: String, val done: Boolean)
data class Goal(val text: String, val priority: String, val steps: List<GoalStep>)

/**
 * Longer-running objectives, distinct from NovaMemoryStore's one-off facts — a goal
 * can have its own steps that get checked off individually over multiple
 * conversations, ported from Elara's set_goal/list_goals/update_goal_progress.
 */
class GoalStore(context: Context) {
    private val prefs = context.getSharedPreferences("nova_goals", Context.MODE_PRIVATE)

    fun allGoals(): List<Goal> {
        val raw = prefs.getString(KEY_GOALS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArray.length()).map { j ->
                val stepObj = stepsArray.getJSONObject(j)
                GoalStep(stepObj.getString("text"), stepObj.getBoolean("done"))
            }
            Goal(obj.getString("text"), obj.optString("priority", "medium"), steps)
        }
    }

    fun addGoal(text: String, priority: String, stepTexts: List<String>) {
        val current = allGoals().toMutableList()
        current.add(Goal(text.trim(), priority.ifBlank { "medium" }, stepTexts.map { GoalStep(it.trim(), false) }))
        save(current)
    }

    /** Marks a step done on the goal whose text best matches textFragment. Returns true if a match was found. */
    fun markStepDone(goalFragment: String, stepFragment: String): Boolean {
        val current = allGoals().toMutableList()
        val goalIndex = current.indexOfFirst { it.text.contains(goalFragment, ignoreCase = true) }
        if (goalIndex == -1) return false

        val goal = current[goalIndex]
        if (stepFragment.isBlank()) {
            // No specific step named — mark the whole goal's remaining steps done, or
            // if it has no steps, there's nothing granular to check off.
            if (goal.steps.isEmpty()) return false
            current[goalIndex] = goal.copy(steps = goal.steps.map { it.copy(done = true) })
            save(current)
            return true
        }

        val stepIndex = goal.steps.indexOfFirst { it.text.contains(stepFragment, ignoreCase = true) }
        if (stepIndex == -1) return false
        val updatedSteps = goal.steps.toMutableList()
        updatedSteps[stepIndex] = updatedSteps[stepIndex].copy(done = true)
        current[goalIndex] = goal.copy(steps = updatedSteps)
        save(current)
        return true
    }

    private fun save(goals: List<Goal>) {
        val array = JSONArray()
        goals.forEach { goal ->
            val stepsArray = JSONArray()
            goal.steps.forEach { stepsArray.put(JSONObject().put("text", it.text).put("done", it.done)) }
            array.put(JSONObject().put("text", goal.text).put("priority", goal.priority).put("steps", stepsArray))
        }
        prefs.edit().putString(KEY_GOALS, array.toString()).apply()
    }

    companion object {
        private const val KEY_GOALS = "goals"
    }
}
