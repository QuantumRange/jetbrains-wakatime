/* ==========================================================
File:        CustomProjectManagerListener.java
Description: Hooks project lifecycle to subscribe per-project activity listeners.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

public class CustomProjectManagerListener implements ProjectManagerListener {
    @Override
    public void projectOpened(@NotNull Project project) {
        WakaTime.subscribeProjectListeners(project);
    }

    @Override
    public void projectClosed(@NotNull Project project) {
        WakaTime.forgetProjectListeners(project);
    }
}
