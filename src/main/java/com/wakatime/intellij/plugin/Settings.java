/* ==========================================================
File:        Settings.java
Description: Prompts user for api key if it does not exist.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class Settings extends DialogWrapper {
    private final JPanel panel;
    private final JLabel apiKeyLabel;
    private final JTextField apiKey;
    private final JLabel proxyLabel;
    private final JTextField proxy;
    private final JLabel debugLabel;
    private final JCheckBox debug;
    private final JLabel statusBarLabel;
    private final JCheckBox statusBar;
    private final JLabel buildingLabel;
    private final JCheckBox building;
    private final JLabel aiCodingLabel;
    private final JCheckBox aiCoding;
    private final JLabel debuggingLabel;
    private final JCheckBox debugging;
    private final JLabel runningTestsLabel;
    private final JCheckBox runningTests;

    public Settings(@Nullable Project project) {
        super(project, true);
        setTitle("WakaTime Settings");
        setOKButtonText("Save");
        panel = new JPanel();
        panel.setLayout(new GridLayout(0,2));

        apiKeyLabel = new JLabel("API key:", JLabel.CENTER);
        panel.add(apiKeyLabel);
        apiKey = new JTextField(36);
        apiKey.setText(ConfigFile.getApiKey());
        panel.add(apiKey);

        proxyLabel = new JLabel("Proxy:", JLabel.CENTER);
        panel.add(proxyLabel);
        proxy = new JTextField();
        String p = ConfigFile.get("settings", "proxy", false);
        if (p == null) p = "";
        proxy.setText(p);
        panel.add(proxy);

        statusBarLabel = new JLabel("Show WakaTime in status bar:", JLabel.CENTER);
        panel.add(statusBarLabel);
        String statusBarValue = ConfigFile.get("settings", "status_bar_enabled", false);
        statusBar = new JCheckBox();
        statusBar.setSelected(statusBarValue == null || !statusBarValue.trim().toLowerCase().equals("false"));
        panel.add(statusBar);

        debugLabel = new JLabel("Debug:", JLabel.CENTER);
        panel.add(debugLabel);
        String debugValue = ConfigFile.get("settings", "debug", false);
        debug = new JCheckBox();
        debug.setSelected(debugValue != null && debugValue.trim().toLowerCase().equals("true"));
        panel.add(debug);

        buildingLabel = new JLabel("Track time spent building:", JLabel.CENTER);
        panel.add(buildingLabel);
        String buildingValue = ConfigFile.getModSetting("building_enabled");
        building = new JCheckBox();
        building.setSelected(buildingValue != null && buildingValue.trim().toLowerCase().equals("true"));
        panel.add(building);

        aiCodingLabel = new JLabel("Track time spent AI coding (heuristic):", JLabel.CENTER);
        panel.add(aiCodingLabel);
        String aiCodingValue = ConfigFile.getModSetting("ai_coding_enabled");
        aiCoding = new JCheckBox();
        aiCoding.setSelected(aiCodingValue != null && aiCodingValue.trim().toLowerCase().equals("true"));
        panel.add(aiCoding);

        debuggingLabel = new JLabel("Track time spent debugging:", JLabel.CENTER);
        panel.add(debuggingLabel);
        String debuggingValue = ConfigFile.getModSetting("debugging_enabled");
        debugging = new JCheckBox();
        debugging.setSelected(debuggingValue != null && debuggingValue.trim().toLowerCase().equals("true"));
        panel.add(debugging);

        runningTestsLabel = new JLabel("Track time spent running tests:", JLabel.CENTER);
        panel.add(runningTestsLabel);
        String runningTestsValue = ConfigFile.getModSetting("running_tests_enabled");
        runningTests = new JCheckBox();
        runningTests.setSelected(runningTestsValue != null && runningTestsValue.trim().toLowerCase().equals("true"));
        panel.add(runningTests);

        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return panel;
    }

    @Override
    protected ValidationInfo doValidate() {
        try {
            UUID.fromString(apiKey.getText().replaceFirst("^waka_", ""));
        } catch (Exception e) {
            return new ValidationInfo("Invalid api key.");
        }
        return null;
    }

    @Override
    public void doOKAction() {
        ConfigFile.setApiKey(apiKey.getText());
        ConfigFile.set("settings", "proxy", false, proxy.getText());
        ConfigFile.set("settings", "debug", false, debug.isSelected() ? "true" : "false");
        ConfigFile.set("settings", "status_bar_enabled", false, statusBar.isSelected() ? "true" : "false");
        ConfigFile.setModSetting("building_enabled", building.isSelected() ? "true" : "false");
        ConfigFile.setModSetting("ai_coding_enabled", aiCoding.isSelected() ? "true" : "false");
        ConfigFile.setModSetting("debugging_enabled", debugging.isSelected() ? "true" : "false");
        ConfigFile.setModSetting("running_tests_enabled", runningTests.isSelected() ? "true" : "false");
        WakaTime.setupConfigs();
        WakaTime.setupStatusBar();
        WakaTime.setLoggingLevel();
        super.doOKAction();
    }

}
