package com.example.voicelistener

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class TaskerTask(
    val id: String,
    val name: String,
    val taskName: String,
    val par1: String = "",
    val par2: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("taskName", taskName)
        put("par1", par1)
        put("par2", par2)
    }

    companion object {
        fun fromJson(obj: JSONObject): TaskerTask = TaskerTask(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            taskName = obj.optString("taskName", ""),
            par1 = obj.optString("par1", ""),
            par2 = obj.optString("par2", "")
        )
    }
}

object TaskerHelper {
    private const val PREFS_KEY = "tasker_tasks"
    private const val ACTION_PREFIX = "tasker_"
    private val TASKER_PACKAGES = listOf("net.dinglisch.android.taskerm", "net.dinglisch.android.tasker")

    fun getTasks(context: Context): List<TaskerTask> {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { TaskerTask.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveTasks(context: Context, tasks: List<TaskerTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY, arr.toString()).apply()
    }

    fun addTask(context: Context, task: TaskerTask) {
        val tasks = getTasks(context).toMutableList()
        tasks.add(task)
        saveTasks(context, tasks)
    }

    fun removeTask(context: Context, taskId: String) {
        val tasks = getTasks(context).filter { it.id != taskId }
        saveTasks(context, tasks)
    }

    fun updateTask(context: Context, task: TaskerTask) {
        val tasks = getTasks(context).toMutableList()
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) tasks[idx] = task
        saveTasks(context, tasks)
    }

    fun generateId(): String = "t_${System.currentTimeMillis()}"

    fun actionIdForTask(task: TaskerTask): String = "$ACTION_PREFIX${task.id}"

    fun isTaskerAction(actionId: String): Boolean = actionId.startsWith(ACTION_PREFIX)

    fun getTaskForAction(context: Context, actionId: String): TaskerTask? {
        val taskId = actionId.removePrefix(ACTION_PREFIX)
        return getTasks(context).find { it.id == taskId }
    }

    /** Build a map of actionId -> label for use in spinners etc. */
    fun getActionLabels(context: Context): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (task in getTasks(context)) {
            map[actionIdForTask(task)] = "Tasker: ${task.name}"
        }
        return map
    }

    /** Find installed Tasker package */
    private fun findTaskerPackage(context: Context): String? {
        for (pkg in TASKER_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    /** Execute a Tasker task using official implicit broadcast API.
     *  Requires PERMISSION_RUN_TASKS in AndroidManifest.xml and
     *  "Allow External Access" enabled in Tasker preferences. */
    fun executeTask(context: Context, task: TaskerTask): Boolean {
        val tag = "TaskerHelper"
        val pkg = findTaskerPackage(context)
        if (pkg == null) {
            android.util.Log.e(tag, "Tasker ist nicht installiert!")
            return false
        }
        android.util.Log.d(tag, "Tasker gefunden: $pkg, sende Task '${task.taskName}'")

        return try {
            // Official Tasker API: implicit broadcast with random data URI
            // See: https://tasker.joaoapps.com/invoketasks.html
            val intent = Intent("net.dinglisch.android.tasker.ACTION_TASK").apply {
                data = Uri.parse("id:${System.currentTimeMillis()}")
                putExtra("task_name", task.taskName)
                if (task.par1.isNotEmpty()) putExtra("par1", task.par1)
                if (task.par2.isNotEmpty()) putExtra("par2", task.par2)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                // No setComponent / setPackage — Tasker registers its receiver dynamically
            }
            context.sendBroadcast(intent)
            android.util.Log.d(tag, "Tasker broadcast gesendet: task='${task.taskName}', par1='${task.par1}', par2='${task.par2}'")
            true
        } catch (e: Exception) {
            android.util.Log.e(tag, "Tasker broadcast fehlgeschlagen: ${e.message}")
            false
        }
    }
}
