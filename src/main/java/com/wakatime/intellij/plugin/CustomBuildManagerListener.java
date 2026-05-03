/* ==========================================================
File:        CustomExecutionListener.java
Description: Logs time from compile and build events.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.compiler.server.BuildManagerListener;
import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.EventQueue;
import java.util.UUID;

public class CustomBuildManagerListener implements BuildManagerListener, CompilationStatusListener {
    private static final String BUILD_MANAGER_ACTIVITY_PREFIX = "build-manager:";

    @Override
    public void buildStarted(@NotNull Project project, @NotNull UUID sessionId, boolean isAutomake) {
        if (!WakaTime.TRACK_BUILDING) return;
        if (!WakaTime.isAppActive()) return;
        if (!WakaTime.isProjectInitialized(project)) return;
        WakaTime.logTrackedActivityEvent("build-manager-start", BUILD_MANAGER_ACTIVITY_PREFIX + sessionId, WakaTime.CATEGORY_BUILDING, project, "sessionId=" + sessionId + " automake=" + isAutomake);
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                VirtualFile file = WakaTime.getCurrentOrLastFile(project);
                if (file == null) return;
                WakaTime.startActivity(BUILD_MANAGER_ACTIVITY_PREFIX + sessionId, WakaTime.CATEGORY_BUILDING, project);
            }
        });
    }

    @Override
    public void buildFinished(@NotNull Project project, @NotNull UUID sessionId, boolean isAutomake)  {
        WakaTime.logTrackedActivityEvent("build-manager-stop", BUILD_MANAGER_ACTIVITY_PREFIX + sessionId, WakaTime.CATEGORY_BUILDING, project, "sessionId=" + sessionId + " automake=" + isAutomake);
        WakaTime.stopActivity(BUILD_MANAGER_ACTIVITY_PREFIX + sessionId);
    }

    @Override
    public void compilationFinished(boolean aborted, int errors, int warnings, @NotNull CompileContext compileContext) {
        Project project = compileContext.getProject();
        WakaTime.logTrackedActivityEvent("compile-finished", BUILD_MANAGER_ACTIVITY_PREFIX, WakaTime.CATEGORY_BUILDING, project, "aborted=" + aborted + " errors=" + errors + " warnings=" + warnings);
        WakaTime.stopActivitiesByPrefix(BUILD_MANAGER_ACTIVITY_PREFIX);
    }

    @Override
    public void automakeCompilationFinished(int errors, int warnings, @NotNull CompileContext compileContext) {
        Project project = compileContext.getProject();
        WakaTime.logTrackedActivityEvent("automake-finished", BUILD_MANAGER_ACTIVITY_PREFIX, WakaTime.CATEGORY_BUILDING, project, "errors=" + errors + " warnings=" + warnings);
        WakaTime.stopActivitiesByPrefix(BUILD_MANAGER_ACTIVITY_PREFIX);
    }
}
