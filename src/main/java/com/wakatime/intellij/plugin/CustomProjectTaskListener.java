/* ==========================================================
File:        CustomProjectTaskListener.java
Description: Logs time from project task events.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskListener;
import com.intellij.task.ProjectTaskManager;
import org.jetbrains.annotations.NotNull;

public class CustomProjectTaskListener implements ProjectTaskListener {
    private final Project project;

    public CustomProjectTaskListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void started(@NotNull ProjectTaskContext context) {
        if (!WakaTime.TRACK_BUILDING) return;
        if (!WakaTime.isProjectInitialized(project)) return;
        String activityKey = getActivityKey(context.getSessionId());
        WakaTime.logTrackedActivityEvent("project-task-start", activityKey, WakaTime.CATEGORY_BUILDING, project, "sessionId=" + String.valueOf(context.getSessionId()));
        WakaTime.startActivity(activityKey, WakaTime.CATEGORY_BUILDING, project);
    }

    @Override
    public void finished(@NotNull ProjectTaskManager.Result result) {
        String activityKey = getActivityKey(result.getContext().getSessionId());
        WakaTime.logTrackedActivityEvent("project-task-stop", activityKey, WakaTime.CATEGORY_BUILDING, project, "sessionId=" + String.valueOf(result.getContext().getSessionId()) + " aborted=" + result.isAborted() + " errors=" + result.hasErrors());
        WakaTime.stopActivity(activityKey);
    }

    private String getActivityKey(Object sessionId) {
        return "project-task:" + String.valueOf(sessionId);
    }
}
