/* ==========================================================
File:        CustomExecutionListener.java
Description: Logs time from run, debug, and test execution events.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class CustomExecutionListener implements ExecutionListener {
    @Override
    public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment environment, @NotNull ProcessHandler handler) {
        String category = WakaTime.getExecutionCategory(executorId, environment);
        if (category == null) return;
        Project project = environment.getProject();
        if (!WakaTime.isProjectInitialized(project)) return;
        String activityKey = getActivityKey(environment, handler);
        String runProfileName = environment.getRunProfile() != null ? environment.getRunProfile().getName() : null;
        WakaTime.logTrackedActivityEvent("execution-start", activityKey, category, project, "executor=" + executorId + " runProfile=" + String.valueOf(runProfileName));
        WakaTime.startActivity(activityKey, category, project);
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment environment, @NotNull ProcessHandler handler, int exitCode) {
        String activityKey = getActivityKey(environment, handler);
        String category = WakaTime.getExecutionCategory(executorId, environment);
        WakaTime.logTrackedActivityEvent("execution-stop", activityKey, category, environment.getProject(), "executor=" + executorId + " exitCode=" + exitCode);
        WakaTime.stopActivity(activityKey);
    }

    private String getActivityKey(@NotNull ExecutionEnvironment environment, @NotNull ProcessHandler handler) {
        long executionId = environment.getExecutionId();
        if (executionId > 0) {
            return "execution:" + executionId;
        }
        return "execution:" + System.identityHashCode(handler);
    }
}
