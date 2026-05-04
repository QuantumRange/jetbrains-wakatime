/* ==========================================================
File:        WakaTime.java
Description: Automatic time tracking for JetBrains IDEs.
Maintainer:  WakaTime <support@wakatime.com>
License:     BSD, see LICENSE for more details.
Website:     https://wakatime.com/
===========================================================*/

package com.wakatime.intellij.plugin;

import com.intellij.AppTopics;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.DataManager;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.actions.ConsolePropertiesProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.task.ProjectTaskListener;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.net.HttpConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.KeyboardFocusManager;
import java.io.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

public class WakaTime implements ApplicationComponent {

    public static final BigDecimal FREQUENCY = new BigDecimal(2 * 60); // max secs between heartbeats for continuous coding
    private static final int AI_ACTIVITY_TIMEOUT_SECONDS = 30;
    private static final long AI_HUMAN_TYPING_GRACE_PERIOD_MILLIS = 10000;
    private static final int AI_MIN_CHANGED_CHARS = 80;
    private static final int AI_MIN_INSERTED_CHARS = 20;
    private static final int AI_MIN_INSERTED_LINES = 3;
    private static final String AI_ACTIVITY_PREFIX = "ai:";
    public static final String CATEGORY_BUILDING = "building";
    public static final String CATEGORY_AI_CODING = "ai coding";
    public static final String CATEGORY_DEBUGGING = "debugging";
    public static final String CATEGORY_RUNNING_TESTS = "running tests";
    public static final Logger log = Logger.getInstance("WakaTime");

    public static String VERSION;
    public static String IDE_NAME;
    public static String IDE_VERSION;
    public static MessageBusConnection connection;
    public static Boolean DEBUG = false;
    public static Boolean METRICS = false;
    public static Boolean DEBUG_CHECKED = false;
    public static Boolean STATUS_BAR = false;
    public static Boolean READY = false;
    public static Boolean TRACK_BUILDING = false;
    public static Boolean TRACK_AI_CODING = false;
    public static Boolean TRACK_DEBUGGING = false;
    public static Boolean TRACK_RUNNING_TESTS = false;
    public static String lastFile = null;
    public static BigDecimal lastTime = new BigDecimal(0);
    public static Map<String, LineStats> lineStatsCache = new HashMap<String, LineStats>();
    public static Map<String, Integer> humanLineChanges = new HashMap<String, Integer>();
    public static Map<String, Boolean> filesWithHumanTyping = new HashMap<String, Boolean>();
    public static Map<String, Long> lastHumanTypingAt = new HashMap<String, Long>();
    public static Boolean cancelApiKey = false;

    private final int queueTimeoutSeconds = 30;
    private static ConcurrentLinkedQueue<Heartbeat> heartbeatsQueue = new ConcurrentLinkedQueue<Heartbeat>();
    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static ScheduledFuture<?> scheduledFixture;
    private static ScheduledFuture<?> scheduledActivityHeartbeat;
    private static ConcurrentHashMap<String, String> activeActivityCategories = new ConcurrentHashMap<String, String>();
    private static ConcurrentHashMap<String, Project> activeActivityProjects = new ConcurrentHashMap<String, Project>();
    private static ConcurrentHashMap<String, Boolean> projectTaskSubscriptions = new ConcurrentHashMap<String, Boolean>();
    private static ConcurrentHashMap<String, ScheduledFuture<?>> scheduledActivityStops = new ConcurrentHashMap<String, ScheduledFuture<?>>();

    private static class ActivityHeartbeatData {
        final VirtualFile file;
        final Project project;
        final LineStats lineStats;

        ActivityHeartbeatData(@NotNull VirtualFile file, @NotNull Project project, @Nullable LineStats lineStats) {
            this.file = file;
            this.project = project;
            this.lineStats = lineStats;
        }
    }

    public WakaTime() {
    }

    public void initComponent() {
        try {
            // support older IDE versions with deprecated PluginManager
            VERSION = PluginManager.getPlugin(PluginId.getId("com.wakatime.intellij.plugin")).getVersion();
        } catch (Exception e) {
            // use PluginManagerCore if PluginManager deprecated
            VERSION = PluginManagerCore.getPlugin(PluginId.getId("com.wakatime.intellij.plugin")).getVersion();
        }
        log.info("Initializing WakaTime plugin v" + VERSION + " (https://wakatime.com/)");
        //System.out.println("Initializing WakaTime plugin v" + VERSION + " (https://wakatime.com/)");

        // Set runtime constants
        IDE_NAME = ApplicationNamesInfo.getInstance().getFullProductName().replaceAll(" ", "").toLowerCase();
        IDE_VERSION = ApplicationInfo.getInstance().getFullVersion();

        setupConfigs();
        setLoggingLevel();
        setupStatusBar();
        checkCli();
        setupEventListeners();
        setupQueueProcessor();
    }

    private void checkCli() {
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            public void run() {
                if (!Dependencies.isCLIInstalled()) {
                    log.info("Downloading and installing wakatime-cli...");
                    Dependencies.installCLI();
                    WakaTime.READY = true;
                    log.info("Finished downloading and installing wakatime-cli.");
                } else if (Dependencies.isCLIOld()) {
                    if (System.getenv("WAKATIME_CLI_LOCATION") != null && !System.getenv("WAKATIME_CLI_LOCATION").trim().isEmpty()) {
                        File wakatimeCLI = new File(System.getenv("WAKATIME_CLI_LOCATION"));
                        if (wakatimeCLI.exists()) {
                          log.warn("$WAKATIME_CLI_LOCATION is out of date, please update it.");
                        }
                    } else {
                        log.info("Upgrading wakatime-cli ...");
                        Dependencies.installCLI();
                        WakaTime.READY = true;
                        log.info("Finished upgrading wakatime-cli.");
                    }
                } else {
                    WakaTime.READY = true;
                    log.info("wakatime-cli is up to date.");
                }
                Dependencies.createSymlink(Dependencies.combinePaths(Dependencies.getResourcesLocation(), "wakatime-cli"), Dependencies.getCLILocation());
                log.debug("wakatime-cli location: " + Dependencies.getCLILocation());
            }
        });
    }

    private void setupEventListeners() {
        ApplicationManager.getApplication().invokeLater(new Runnable(){
            public void run() {
                Disposable disposable = Disposer.newDisposable("WakaTimeListener");
                connection = ApplicationManager.getApplication().getMessageBus().connect();

                // save file
                connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, new CustomSaveListener());

                // edit document
                EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new CustomDocumentListener(), disposable);

                // mouse press
                EditorFactory.getInstance().getEventMulticaster().addEditorMouseListener(new CustomEditorMouseListener(), disposable);

                // scroll document
                EditorFactory.getInstance().getEventMulticaster().addVisibleAreaListener(new CustomVisibleAreaListener(), disposable);

                // caret moved
                EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CustomCaretListener(), disposable);

                // execution lifecycle
                connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new CustomExecutionListener());
                connection.subscribe(ProjectManager.TOPIC, new CustomProjectManagerListener());

                for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                    subscribeProjectListeners(project);
                }
            }
        });
    }

    private void setupQueueProcessor() {
        final Runnable handler = new Runnable() {
            public void run() {
                processHeartbeatQueue();
            }
        };
        long delay = queueTimeoutSeconds;
        scheduledFixture = scheduler.scheduleAtFixedRate(handler, delay, delay, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void checkDebug() {
        if (DEBUG_CHECKED) return;
        DEBUG_CHECKED = true;
        if (!DEBUG) return;
        ApplicationManager.getApplication().invokeLater(new Runnable(){
            public void run() {
                Messages.showWarningDialog("Your IDE may respond slower. Disable debug mode from Tools -> WakaTime Settings.", "WakaTime Debug Mode Enabled");
            }
        });
    }

    public void disposeComponent() {
        try {
            if (connection != null) connection.disconnect();
        } catch(Exception e) { }
        try {
            scheduledFixture.cancel(true);
        } catch (Exception e) { }
        try {
            if (scheduledActivityHeartbeat != null) scheduledActivityHeartbeat.cancel(true);
        } catch (Exception e) { }

        // make sure to send all heartbeats before exiting
        processHeartbeatQueue();
    }

    public static void checkApiKey() {
        if (WakaTime.cancelApiKey || ApiKey.isDialogOpened) return;
        ApplicationManager.getApplication().invokeLater(new Runnable(){
            public void run() {
                // prompt for apiKey if it does not already exist
                Project project = getCurrentProject();
                if (project == null) return;
                if (ConfigFile.getApiKey().equals("") && !ConfigFile.usingVaultCmd()) {
                    Application app = ApplicationManager.getApplication();
                    if (app.isUnitTestMode() || !app.isDispatchThread()) return;
                    try {
                        ApiKey apiKey = new ApiKey(project);
                        apiKey.promptForApiKey();
                    } catch(Exception e) {
                        warnException(e);
                    } catch (Throwable throwable) {
                        log.warn("Unable to prompt for api key because UI not ready.");
                    }
                }
            }
        });
    }

    public static BigDecimal getCurrentTimestamp() {
        return new BigDecimal(String.valueOf(System.currentTimeMillis() / 1000.0)).setScale(4, BigDecimal.ROUND_HALF_UP);
    }

    public static void appendHeartbeat(final VirtualFile file, final Project project, final boolean isWrite, @Nullable final LineStats lineStats) {
        appendHeartbeat(file, project, isWrite, lineStats, false);
    }

    public static void appendHeartbeat(final VirtualFile file, final Project project, final boolean isWrite, @Nullable final LineStats lineStats, final boolean force) {
        checkDebug();

        if (!shouldLogFile(file)) return;

        String filePath = file.getPath();

        if (filePath.contains("/var/cache/")) {
            return;
        }

        if (WakaTime.READY) {
            updateStatusBarText();
            if (project != null) {
                StatusBar statusbar = WindowManager.getInstance().getStatusBar(project);
                if (statusbar != null) statusbar.updateWidget("WakaTime");
            }
        }

        final BigDecimal time = WakaTime.getCurrentTimestamp();
        if (!force && !isWrite && filePath.equals(WakaTime.lastFile) && !enoughTimePassed(time)) {
            return;
        }

        WakaTime.lastFile = filePath;
        WakaTime.lastTime = time;

        final String projectName = project != null ? project.getName() : null;
        final String language = WakaTime.getLanguage(file);

        String localFile = null;
        if (file.getFileSystem().getProtocol().equals("cwm")) {
            try {
                byte[] content = file.contentsToByteArray(true);
                File tempFile = FileUtil.createTempFile("wakatime.", file.getName());
                FileUtil.writeToFile(tempFile, content);
                localFile = tempFile.getAbsolutePath();
            } catch (IOException e) {
                warnException(e);
                return;
            }
        }
        final String localFilePath = localFile;
        final Integer humanLineChanges = popHumanLineChanges(filePath);

        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            public void run() {
                Heartbeat h = new Heartbeat();
                h.entity = filePath;
                h.timestamp = time;
                h.isWrite = isWrite;
                h.isUnsavedFile = !file.exists();
                h.project = projectName;
                h.language = language;
                h.category = WakaTime.getCurrentActivityCategory();
                if (lineStats != null) {
                    h.lineCount = lineStats.lineCount;
                    h.lineNumber = lineStats.lineNumber;
                    h.cursorPosition = lineStats.cursorPosition;
                }
                h.humanLineChanges = humanLineChanges;

                if (localFilePath != null) {
                    h.localFile = localFilePath;
                }

                heartbeatsQueue.add(h);
                logTrackedActivityHeartbeat("queue", h.category, project, file, force, lineStats);

                if (h.category != null) ensureActivityHeartbeat();
            }
        });
    }

    private static synchronized void ensureActivityHeartbeat() {
        if (scheduledActivityHeartbeat != null && !scheduledActivityHeartbeat.isDone()) return;
        scheduledActivityHeartbeat = AppExecutorUtil.getAppScheduledExecutorService().schedule(new Runnable() {
            @Override
            public void run() {
                scheduledActivityHeartbeat = null;
                if (WakaTime.getCurrentActivityCategory() == null) return;
                WakaTime.appendActivityHeartbeat(WakaTime.getCurrentActivityProject(), true);
            }
        }, 10, TimeUnit.SECONDS);
    }

    private static void processHeartbeatQueue() {
        if (!WakaTime.READY) return;
        if (pluginString() == null) return;

        checkApiKey();

        // get single heartbeat from queue
        Heartbeat heartbeat = heartbeatsQueue.poll();
        if (heartbeat == null)
            return;

        // get all extra heartbeats from queue
        ArrayList<Heartbeat> extraHeartbeats = new ArrayList<>();
        while (true) {
            Heartbeat h = heartbeatsQueue.poll();
            if (h == null)
                break;
            extraHeartbeats.add(h);
        }

        sendHeartbeat(heartbeat, extraHeartbeats);
    }

    private static void sendHeartbeat(final Heartbeat heartbeat, final ArrayList<Heartbeat> extraHeartbeats) {
        final String[] cmds = buildCliCommand(heartbeat, extraHeartbeats);
        if (cmds.length == 0) {
            return;
        }
        log.debug("Executing CLI: " + Arrays.toString(obfuscateKey(cmds)));
        try {
            Process proc = Runtime.getRuntime().exec(cmds);
            if (extraHeartbeats.size() > 0) {
                String json = toJSON(extraHeartbeats);
                log.debug(json);
                try {
                    BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
                    stdin.write(json);
                    stdin.write("\n");
                    try {
                        stdin.flush();
                        stdin.close();
                    } catch (IOException e) { /* ignored because wakatime-cli closes pipe after receiving \n */ }
                } catch (IOException e) {
                    warnException(e);
                }
            }
            if (WakaTime.DEBUG) {
                BufferedReader stdout = new BufferedReader(new
                        InputStreamReader(proc.getInputStream()));
                BufferedReader stderr = new BufferedReader(new
                        InputStreamReader(proc.getErrorStream()));
                proc.waitFor();
                String s;
                while ((s = stdout.readLine()) != null) {
                    log.debug(s);
                }
                while ((s = stderr.readLine()) != null) {
                    log.debug(s);
                }
                log.debug("Command finished with return value: " + proc.exitValue());
            }
        } catch (Exception e) {
            warnException(e);
            if (Dependencies.isWindows() && e.toString().contains("Access is denied")) {
                try {
                    Messages.showWarningDialog("Microsoft Defender is blocking WakaTime. Please allow " + Dependencies.getCLILocation() + " to run so WakaTime can upload code stats to your dashboard.", "Error");
                } catch (Exception ex) { }
            }
        }
    }

    private static String toJSON(ArrayList<Heartbeat> extraHeartbeats) {
        StringBuffer json = new StringBuffer();
        json.append("[");
        boolean first = true;
        for (Heartbeat heartbeat : extraHeartbeats) {
            StringBuffer h = new StringBuffer();
            h.append("{\"entity\":\"");
            h.append(jsonEscape(heartbeat.entity));
            h.append("\",\"timestamp\":");
            h.append(heartbeat.timestamp.toPlainString());
            h.append(",\"is_write\":");
            h.append(heartbeat.isWrite.toString());
            if (heartbeat.lineCount != null) {
                h.append(",\"lines\":");
                h.append(heartbeat.lineCount);
            }
            if (heartbeat.lineNumber != null) {
                h.append(",\"lineno\":");
                h.append(heartbeat.lineNumber);
            }
            if (heartbeat.cursorPosition != null) {
                h.append(",\"cursorpos\":");
                h.append(heartbeat.cursorPosition);
            }
            if (heartbeat.humanLineChanges != null && heartbeat.humanLineChanges != 0) {
                h.append(",\"human_line_changes\":");
                h.append(heartbeat.humanLineChanges);
            }
            if (heartbeat.isUnsavedFile) {
                h.append(",\"is_unsaved_entity\":true");
            }
            if (heartbeat.category != null) {
                h.append(",\"category\":\"");
                h.append(jsonEscape(heartbeat.category));
                h.append("\"");
            }
            if (heartbeat.project != null) {
                h.append(",\"alternate_project\":\"");
                h.append(jsonEscape(heartbeat.project));
                h.append("\"");
            }
            if (heartbeat.language != null) {
                h.append(",\"language\":\"");
                h.append(jsonEscape(heartbeat.language));
                h.append("\"");
            }
            if (heartbeat.localFile != null) {
                h.append(",\"local_file\":\"");
                h.append(jsonEscape(heartbeat.localFile));
                h.append("\"");
            }
            h.append("}");
            if (!first)
                json.append(",");
            json.append(h);
            first = false;
        }
        json.append("]");
        return json.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null)
            return null;
        StringBuffer escaped = new StringBuffer();
        final int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch(c) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    boolean isUnicode = (c >= '\u0000' && c <= '\u001F') || (c >= '\u007F' && c <= '\u009F') || (c >= '\u2000' && c <= '\u20FF');
                    if (isUnicode){
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = 0; k < 4 - hex.length(); k++) {
                            escaped.append('0');
                        }
                        escaped.append(hex.toUpperCase());
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    private static String[] buildCliCommand(Heartbeat heartbeat, ArrayList<Heartbeat> extraHeartbeats) {
        ArrayList<String> cmds = new ArrayList<String>();
        cmds.add(Dependencies.getCLILocation());
        cmds.add("--plugin");
        String plugin = pluginString();
        if (plugin == null) {
            return new String[0];
        }
        cmds.add(pluginString());
        cmds.add("--entity");
        cmds.add(heartbeat.entity);
        if (heartbeat.localFile != null) {
            cmds.add("--local-file");
            cmds.add(heartbeat.localFile);
        }
        cmds.add("--time");
        cmds.add(heartbeat.timestamp.toPlainString());
        String apiKey = ConfigFile.getApiKey();
        if (!apiKey.equals("")) {
            cmds.add("--key");
            cmds.add(apiKey);
        }
        if (heartbeat.lineCount != null) {
            cmds.add("--lines-in-file");
            cmds.add(heartbeat.lineCount.toString());
        }
        if (heartbeat.lineNumber != null) {
            cmds.add("--lineno");
            cmds.add(heartbeat.lineNumber.toString());
        }
        if (heartbeat.cursorPosition != null) {
            cmds.add("--cursorpos");
            cmds.add(heartbeat.cursorPosition.toString());
        }
        if (heartbeat.humanLineChanges != null && heartbeat.humanLineChanges != 0) {
            cmds.add("--human-line-changes");
            cmds.add(heartbeat.humanLineChanges.toString());
        }
        if (heartbeat.project != null) {
            cmds.add("--alternate-project");
            cmds.add(heartbeat.project);
        }
        if (heartbeat.language != null) {
            cmds.add("--alternate-language");
            cmds.add(heartbeat.language);
        }
        if (heartbeat.isWrite)
            cmds.add("--write");
        if (heartbeat.isUnsavedFile)
            cmds.add("--is-unsaved-entity");
        if (heartbeat.category != null) {
            cmds.add("--category");
            cmds.add(heartbeat.category);
        }
        if (WakaTime.METRICS)
            cmds.add("--metrics");

        String proxy = getBuiltinProxy();
        if (proxy != null) {
            WakaTime.log.info("built-in proxy will be used: " + proxy);
            cmds.add("--proxy");
            cmds.add(proxy);
        }

        if (extraHeartbeats.size() > 0)
            cmds.add("--extra-heartbeats");
        return cmds.toArray(new String[cmds.size()]);
    }

    private static String pluginString() {
        if (IDE_NAME == null || IDE_NAME.equals("")) {
            return null;
        }

        return IDE_NAME+"/"+IDE_VERSION+" "+IDE_NAME+"-wakatime/"+VERSION;
    }

    private static String getBuiltinProxy() {
        HttpConfigurable config = HttpConfigurable.getInstance();

        if (!config.isHttpProxyEnabledForUrl("https://api.wakatime.com")) return null;

        String host = config.PROXY_HOST;
        if (host != null) {
            String auth = "";
            String protocol = config.PROXY_TYPE_IS_SOCKS ? "socks5://" : "https://";

            String user = null;
            try {
                user = config.getProxyLogin();
                if (user != null) {
                    auth = String.format("%s:%s@", user, config.getPlainProxyPassword());
                }
            } catch (NoSuchMethodError e) { }

            String url = protocol + auth + host;
            if (config.PROXY_PORT > 0) {
                url += String.format(":%d", config.PROXY_PORT);
            }

            return url;
        }

        return null;
    }

    public static boolean enoughTimePassed(BigDecimal currentTime) {
        return WakaTime.lastTime.add(FREQUENCY).compareTo(currentTime) < 0;
    }

    public static boolean shouldLogFile(VirtualFile file) {
        if (file == null || file.getUrl().startsWith("mock://")) {
            return false;
        }
        String filePath = file.getPath();
        if (filePath.equals("atlassian-ide-plugin.xml") || filePath.contains("/.idea/workspace.xml")) {
            return false;
        }
        return true;
    }

    public static boolean isAppActive() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
    }

    public static boolean isProjectInitialized(Project project) {
        if (project == null) return true;
        return project.isInitialized();
    }

    public static void setupConfigs() {
        String debug = ConfigFile.get("settings", "debug", false);
        WakaTime.DEBUG = debug != null && debug.trim().equals("true");
        String metrics = ConfigFile.get("settings", "metrics", false);
        WakaTime.METRICS = metrics != null && metrics.trim().equals("true");
        String building = ConfigFile.getModSetting("building_enabled");
        WakaTime.TRACK_BUILDING = building != null && building.trim().equals("true");
        String aiCoding = ConfigFile.getModSetting("ai_coding_enabled");
        WakaTime.TRACK_AI_CODING = aiCoding != null && aiCoding.trim().equals("true");
        String debugging = ConfigFile.getModSetting("debugging_enabled");
        WakaTime.TRACK_DEBUGGING = debugging != null && debugging.trim().equals("true");
        String runningTests = ConfigFile.getModSetting("running_tests_enabled");
        WakaTime.TRACK_RUNNING_TESTS = runningTests != null && runningTests.trim().equals("true");
        if (!WakaTime.TRACK_BUILDING) clearActivityCategory(CATEGORY_BUILDING);
        if (!WakaTime.TRACK_AI_CODING) clearActivityCategory(CATEGORY_AI_CODING);
        if (!WakaTime.TRACK_DEBUGGING) clearActivityCategory(CATEGORY_DEBUGGING);
        if (!WakaTime.TRACK_RUNNING_TESTS) clearActivityCategory(CATEGORY_RUNNING_TESTS);
    }

    public static void setupStatusBar() {
        String statusBarVal = ConfigFile.get("settings", "status_bar_enabled", false);
        WakaTime.STATUS_BAR = statusBarVal == null || !statusBarVal.trim().equals("false");
        if (WakaTime.READY) {
            try {
                updateStatusBarText();
                Project project = getCurrentProject();
                if (project == null) return;
                StatusBar statusbar = WindowManager.getInstance().getStatusBar(project);
                if (statusbar == null) return;
                statusbar.updateWidget("WakaTime");
            } catch (Exception e) {
                warnException(e);
            }
        }
    }

    public static void setLoggingLevel() {
        /*
        try {
            if (WakaTime.DEBUG) {
                log.setLevel(LogLevel.DEBUG);
                log.debug("Logging level set to DEBUG");
            } else {
                log.setLevel(LogLevel.INFO);
            }
        } catch(Throwable e) {
            System.out.println(e.getStackTrace());
        }
        */
    }

    private static String getLanguage(final VirtualFile file) {
        FileType type = file.getFileType();
        if (type != null)
            return type.getName();
        return null;
    }

    @Nullable
    public static VirtualFile getFile(Document document) {
        if (document == null) return null;
        FileDocumentManager instance = FileDocumentManager.getInstance();
        if (instance == null) return null;
        VirtualFile file = instance.getFile(document);
        return file;
    }

    @Nullable
    public static VirtualFile getCurrentFile(Project project) {
        if (project == null) return null;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return null;
        Document document = editor.getDocument();
        return WakaTime.getFile(document);
    }

    @Nullable
    public static VirtualFile getCurrentOrLastFile(Project project) {
        VirtualFile file = WakaTime.getCurrentFile(project);
        if (file != null) {
            return file;
        }
        if (WakaTime.lastFile == null) {
            return null;
        }
        return LocalFileSystem.getInstance().findFileByPath(WakaTime.lastFile);
    }

    public static Project getProject(Document document) {
        Editor[] editors = EditorFactory.getInstance().getEditors(document);
        if (editors.length > 0) {
            return editors[0].getProject();
        }
        return null;
    }

    @Nullable
    public static Document getCurrentDocument(Project project) {
        if (project == null) return null;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return null;
        return editor.getDocument();
    }

    @Nullable
    public static Project getCurrentProject() {
        try {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project != null && project.isInitialized() && WakaTime.getCurrentFile(project) != null) {
                    return project;
                }
            }
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length > 0) {
                return openProjects[0];
            }
        } catch (Exception e) { }
        return null;
    }

    public static void subscribeProjectListeners(@Nullable Project project) {
        if (project == null || project.isDisposed()) return;
        String locationHash = project.getLocationHash();
        if (locationHash != null && projectTaskSubscriptions.putIfAbsent(locationHash, true) != null) return;
        project.getMessageBus().connect(project).subscribe(ProjectTaskListener.TOPIC, new CustomProjectTaskListener(project));
    }

    public static void forgetProjectListeners(@Nullable Project project) {
        if (project == null) return;
        String locationHash = project.getLocationHash();
        if (locationHash != null) {
            projectTaskSubscriptions.remove(locationHash);
        }
    }

    public static void appendActivityHeartbeat(@Nullable Project preferredProject, boolean force) {
        String category = WakaTime.getCurrentActivityCategory();
        if (category == null) return;
        Application app = ApplicationManager.getApplication();
        if (app == null) return;
        ActivityHeartbeatData data;
        if (app.isDispatchThread()) {
            data = app.runReadAction(new Computable<ActivityHeartbeatData>() {
                @Override
                public ActivityHeartbeatData compute() {
                    return collectActivityHeartbeatDataOnEdt(preferredProject);
                }
            });
        } else {
            data = app.runReadAction(new Computable<ActivityHeartbeatData>() {
                @Override
                public ActivityHeartbeatData compute() {
                    return collectActivityHeartbeatDataOffEdt(preferredProject);
                }
            });
        }
        if (data == null) {
            logTrackedActivityEvent("heartbeat-skip", null, category, preferredProject, "reason=no-activity-file");
            return;
        }
        logTrackedActivityHeartbeat("heartbeat-queue", category, data.project, data.file, force, data.lineStats);
        WakaTime.appendHeartbeat(data.file, data.project, false, data.lineStats, force);
    }

    @Nullable
    private static ActivityHeartbeatData collectActivityHeartbeatDataOnEdt(@Nullable Project preferredProject) {
        Project project = getPreferredActivityProject(preferredProject, true);
        if (project == null || !WakaTime.isProjectInitialized(project)) return null;
        VirtualFile file = WakaTime.getCurrentOrLastFile(project);
        if (file == null) return null;
        return new ActivityHeartbeatData(file, project, WakaTime.getLineStats(file));
    }

    @Nullable
    private static ActivityHeartbeatData collectActivityHeartbeatDataOffEdt(@Nullable Project preferredProject) {
        Project project = getPreferredActivityProject(preferredProject, false);
        if (project == null || !WakaTime.isProjectInitialized(project)) return null;
        VirtualFile file = getLastTrackedFile();
        if (file == null) return null;
        return new ActivityHeartbeatData(file, project, getCachedLineStats(file));
    }

    @Nullable
    private static Project getPreferredActivityProject(@Nullable Project preferredProject, boolean allowCurrentProjectLookup) {
        Project project = preferredProject;
        if (project == null || project.isDisposed()) {
            project = WakaTime.getCurrentActivityProject();
        }
        if ((project == null || project.isDisposed()) && allowCurrentProjectLookup) {
            project = WakaTime.getCurrentProject();
        }
        if (project == null || project.isDisposed()) {
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length > 0) {
                project = openProjects[0];
            }
        }
        return project;
    }

    @Nullable
    private static VirtualFile getLastTrackedFile() {
        if (WakaTime.lastFile == null) return null;
        return LocalFileSystem.getInstance().findFileByPath(WakaTime.lastFile);
    }

    @Nullable
    private static LineStats getCachedLineStats(@Nullable VirtualFile file) {
        if (file == null) return null;
        LineStats cached = WakaTime.lineStatsCache.get(file.getPath());
        if (cached == null) return null;
        LineStats copy = new LineStats();
        copy.lineCount = cached.lineCount;
        copy.lineNumber = cached.lineNumber;
        copy.cursorPosition = cached.cursorPosition;
        copy.updatedAt = cached.updatedAt;
        return copy;
    }

    public static synchronized void startActivity(String activityKey, String category, @Nullable Project project) {
        if (!isTrackingEnabled(category)) {
            logTrackedActivityEvent("start-skip", activityKey, category, project, "reason=disabled");
            return;
        }
        String previousCategory = getCurrentActivityCategory();
        activeActivityCategories.put(activityKey, category);
        if (project != null) {
            activeActivityProjects.put(activityKey, project);
        }
        String currentCategory = getCurrentActivityCategory();
        logTrackedActivityEvent("start", activityKey, category, project, "previous=" + safeValue(previousCategory) + " current=" + safeValue(currentCategory));
        if (currentCategory != null && !currentCategory.equals(previousCategory)) {
            appendActivityHeartbeat(project, true);
        }
    }

    public static synchronized void startTimedActivity(String activityKey, String category, @Nullable Project project, int timeoutSeconds) {
        if (!isTrackingEnabled(category)) return;
        startActivity(activityKey, category, project);
        scheduleActivityStop(activityKey, timeoutSeconds);
    }

    public static synchronized void stopActivity(String activityKey) {
        String previousCategory = getCurrentActivityCategory();
        String removedCategory = activeActivityCategories.remove(activityKey);
        activeActivityProjects.remove(activityKey);
        cancelScheduledActivityStop(activityKey);
        String currentCategory = getCurrentActivityCategory();
        logTrackedActivityEvent("stop", activityKey, removedCategory, null, "previous=" + safeValue(previousCategory) + " current=" + safeValue(currentCategory));
        if (currentCategory != null && !currentCategory.equals(previousCategory)) {
            appendActivityHeartbeat(getCurrentActivityProject(), true);
        }
    }

    public static synchronized void stopActivitiesByPrefix(String activityKeyPrefix) {
        String previousCategory = getCurrentActivityCategory();
        int removedCount = 0;
        for (String key : new ArrayList<String>(activeActivityCategories.keySet())) {
            if (key.startsWith(activityKeyPrefix)) {
                activeActivityCategories.remove(key);
                activeActivityProjects.remove(key);
                cancelScheduledActivityStop(key);
                removedCount++;
            }
        }
        String currentCategory = getCurrentActivityCategory();
        if (removedCount > 0) {
            logTrackedActivityEvent("stop-prefix", activityKeyPrefix, previousCategory, null, "removed=" + removedCount + " current=" + safeValue(currentCategory));
        }
        if (currentCategory != null && !currentCategory.equals(previousCategory)) {
            appendActivityHeartbeat(getCurrentActivityProject(), true);
        }
    }

    public static synchronized void clearActivityCategory(String category) {
        String previousCategory = getCurrentActivityCategory();
        for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(activeActivityCategories.entrySet())) {
            if (category.equals(entry.getValue())) {
                activeActivityCategories.remove(entry.getKey());
                activeActivityProjects.remove(entry.getKey());
                cancelScheduledActivityStop(entry.getKey());
            }
        }
        String currentCategory = getCurrentActivityCategory();
        if (currentCategory != null && !currentCategory.equals(previousCategory)) {
            appendActivityHeartbeat(getCurrentActivityProject(), true);
        }
    }

    @Nullable
    public static String getCurrentActivityCategory() {
        String currentCategory = null;
        int currentPriority = Integer.MAX_VALUE;
        for (String category : activeActivityCategories.values()) {
            int priority = getActivityPriority(category);
            if (priority < currentPriority) {
                currentPriority = priority;
                currentCategory = category;
            }
        }
        return currentCategory;
    }

    @Nullable
    public static Project getCurrentActivityProject() {
        String selectedKey = null;
        int currentPriority = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : activeActivityCategories.entrySet()) {
            int priority = getActivityPriority(entry.getValue());
            if (priority < currentPriority) {
                currentPriority = priority;
                selectedKey = entry.getKey();
            }
        }
        if (selectedKey == null) return null;
        Project project = activeActivityProjects.get(selectedKey);
        if (project != null && !project.isDisposed()) {
            return project;
        }
        return null;
    }

    @Nullable
    public static String getExecutionCategory(@NotNull String executorId, @NotNull ExecutionEnvironment environment) {
        if (WakaTime.TRACK_DEBUGGING && DefaultDebugExecutor.EXECUTOR_ID.equals(executorId)) {
            return CATEGORY_DEBUGGING;
        }
        if (WakaTime.TRACK_RUNNING_TESTS && isTestExecution(environment)) {
            return CATEGORY_RUNNING_TESTS;
        }
        if (WakaTime.TRACK_BUILDING && isCargoBuildExecution(environment)) {
            return CATEGORY_BUILDING;
        }
        return null;
    }

    private static boolean isTestExecution(@NotNull ExecutionEnvironment environment) {
        RunProfile runProfile = environment.getRunProfile();
        if (!(runProfile instanceof ConsolePropertiesProvider)) {
            return false;
        }
        try {
            TestConsoleProperties properties = ((ConsolePropertiesProvider) runProfile).createTestConsoleProperties(environment.getExecutor());
            return properties != null;
        } catch (Exception e) {
            debugException(e);
            return false;
        }
    }

    private static final HashSet<String> CARGO_BUILD_COMMANDS = new HashSet<String>(Arrays.asList(
        "build", "check", "clippy", "doc", "rustc"
    ));

    // Detects RustRover/IntelliJ-Rust cargo build-style executions via reflection
    // so the plugin keeps loading on IDEs without the Rust module.
    private static boolean isCargoBuildExecution(@NotNull ExecutionEnvironment environment) {
        RunProfile runProfile = environment.getRunProfile();
        if (runProfile == null) return false;
        try {
            Class<?> clazz = runProfile.getClass();
            boolean isCargo = false;
            while (clazz != null) {
                String name = clazz.getName();
                if ("org.rust.cargo.runconfig.command.CargoCommandConfiguration".equals(name)) {
                    isCargo = true;
                    break;
                }
                clazz = clazz.getSuperclass();
            }
            if (!isCargo) return false;
            Object commandValue = null;
            try {
                java.lang.reflect.Method getter = runProfile.getClass().getMethod("getCommand");
                commandValue = getter.invoke(runProfile);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Field field = runProfile.getClass().getField("command");
                commandValue = field.get(runProfile);
            }
            if (!(commandValue instanceof String)) return false;
            String command = ((String) commandValue).trim().toLowerCase(Locale.ROOT);
            if (command.isEmpty()) return false;
            String head = command.split("\\s+", 2)[0];
            return CARGO_BUILD_COMMANDS.contains(head);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isTrackingEnabled(String category) {
        if (CATEGORY_BUILDING.equals(category)) return WakaTime.TRACK_BUILDING;
        if (CATEGORY_AI_CODING.equals(category)) return WakaTime.TRACK_AI_CODING;
        if (CATEGORY_DEBUGGING.equals(category)) return WakaTime.TRACK_DEBUGGING;
        if (CATEGORY_RUNNING_TESTS.equals(category)) return WakaTime.TRACK_RUNNING_TESTS;
        return false;
    }

    private static int getActivityPriority(String category) {
        if (CATEGORY_AI_CODING.equals(category)) return 0;
        if (CATEGORY_DEBUGGING.equals(category)) return 1;
        if (CATEGORY_RUNNING_TESTS.equals(category)) return 2;
        if (CATEGORY_BUILDING.equals(category)) return 3;
        return Integer.MAX_VALUE;
    }

    private static void scheduleActivityStop(final String activityKey, int timeoutSeconds) {
        cancelScheduledActivityStop(activityKey);
        ScheduledFuture<?> future = AppExecutorUtil.getAppScheduledExecutorService().schedule(new Runnable() {
            @Override
            public void run() {
                scheduledActivityStops.remove(activityKey);
                stopActivity(activityKey);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        scheduledActivityStops.put(activityKey, future);
    }

    private static void cancelScheduledActivityStop(String activityKey) {
        ScheduledFuture<?> future = scheduledActivityStops.remove(activityKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    public static boolean shouldTrackLikelyAiEdit(@NotNull VirtualFile file, @NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
        if (!WakaTime.TRACK_AI_CODING) return false;
        if (WakaTime.hadRecentHumanTyping(file, AI_HUMAN_TYPING_GRACE_PERIOD_MILLIS)) return false;
        if (event.isWholeTextReplaced()) return true;

        int changedChars = Math.max(event.getNewLength(), event.getOldLength());
        if (changedChars >= AI_MIN_CHANGED_CHARS) return true;

        CharSequence newFragment = event.getNewFragment();
        return newFragment.length() >= AI_MIN_INSERTED_CHARS && countLines(newFragment) >= AI_MIN_INSERTED_LINES;
    }

    public static void markFileWithLikelyAiEdit(@NotNull VirtualFile file, @Nullable Project project) {
        WakaTime.startTimedActivity(AI_ACTIVITY_PREFIX + file.getPath(), CATEGORY_AI_CODING, project, AI_ACTIVITY_TIMEOUT_SECONDS);
    }

    public static void stopLikelyAiEdit(@NotNull VirtualFile file) {
        WakaTime.stopActivity(AI_ACTIVITY_PREFIX + file.getPath());
    }

    public static synchronized boolean hadRecentHumanTyping(@NotNull VirtualFile file, long withinMillis) {
        Long lastTypedAt = WakaTime.lastHumanTypingAt.get(file.getPath());
        return lastTypedAt != null && (System.currentTimeMillis() - lastTypedAt) < withinMillis;
    }

    private static int countLines(@NotNull CharSequence text) {
        if (text.length() == 0) return 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public static LineStats getLineStats(@Nullable Document document, @Nullable Editor editor) {
        if (editor == null && document != null) {
            Project project = WakaTime.getProject(document);
            if (project != null && project.isInitialized()) {
                editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            }
        }

        if (editor != null) {
            if (document == null) {
                document = editor.getDocument();
            }
            for (Caret caret : editor.getCaretModel().getAllCarets()) {
                LineStats lineStats = new LineStats();
                if (document != null) {
                    lineStats.lineCount = document.getLineCount();
                }
                LogicalPosition position = caret.getLogicalPosition();
                lineStats.lineNumber = position.line + 1;
                lineStats.cursorPosition = position.column + 1;
                if (lineStats.isOK()) {
                    saveLineStats(document, lineStats, true);
                    return lineStats;
                }
            }
        }

        return WakaTime.getLineStats(document);
    }

    public static LineStats getLineStats(@Nullable Document document) {
        if (document != null) {
            VirtualFile file = WakaTime.getFile(document);
            LineStats lineStats = new LineStats();
            lineStats.lineCount = document.getLineCount();
            Caret caret = getCurrentCaretIfAvailable();
            if (caret != null) {
                LogicalPosition position = caret.getLogicalPosition();
                lineStats.lineNumber = position.line + 1;
                lineStats.cursorPosition = position.column + 1;
            }
            saveLineStats(file, lineStats, true);
            return lineStats;
        }

        return WakaTime.getLineStats(WakaTime.getFile(document));
    }

    public static LineStats getLineStats(@Nullable VirtualFile file) {
        Caret caret = getCurrentCaretIfAvailable();
        LineStats lineStats = new LineStats();
        if (file != null) {
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document != null) {
                lineStats.lineCount = document.getLineCount();
            }
        }

        if (caret != null) {
            LogicalPosition position = caret.getLogicalPosition();
            lineStats.lineNumber = position.line + 1;
            lineStats.cursorPosition = position.column + 1;
            if (lineStats.lineCount == null) {
                Editor editor = caret.getEditor();
                if (editor != null) {
                    Document document = editor.getDocument();
                    if (document != null) {
                        lineStats.lineCount = document.getLineCount();
                    }
                }
            }
        }

        if (lineStats.hasLineCount()) {
            saveLineStats(file, lineStats, true);
            return lineStats;
        }

        if (file == null) return new LineStats();

        return WakaTime.lineStatsCache.get(file.getPath());
    }

    @Nullable
    private static Caret getCurrentCaretIfAvailable() {
        Application app = ApplicationManager.getApplication();
        if (app == null || !app.isDispatchThread()) {
            return null;
        }
        return CommonDataKeys.CARET.getData(DataManager.getInstance().getDataContext());
    }

    public static void saveLineStats(Document document, LineStats lineStats) {
        VirtualFile file = WakaTime.getFile(document);
        saveLineStats(file, lineStats);
    }

    public static void saveLineStats(@Nullable VirtualFile file, LineStats lineStats) {
        saveLineStats(file, lineStats, false);
    }

    public static void saveLineStats(Document document, LineStats lineStats, boolean updateLineChanges) {
        VirtualFile file = WakaTime.getFile(document);
        saveLineStats(file, lineStats, updateLineChanges);
    }

    public static synchronized void saveLineStats(@Nullable VirtualFile file, LineStats lineStats, boolean updateLineChanges) {
        if (file == null) return;
        if (lineStats == null || !lineStats.hasLineCount()) return;
        LineStats previous = WakaTime.lineStatsCache.get(file.getPath());
        if (previous != null) {
            if (lineStats.lineNumber == null) {
                lineStats.lineNumber = previous.lineNumber;
            }
            if (lineStats.cursorPosition == null) {
                lineStats.cursorPosition = previous.cursorPosition;
            }
        }
        lineStats.updatedAt = System.currentTimeMillis();
        if (updateLineChanges) {
            updateLineChanges(file, lineStats);
        }
        WakaTime.lineStatsCache.put(file.getPath(), lineStats);
    }

    private static synchronized void updateLineChanges(@NotNull VirtualFile file, @NotNull LineStats lineStats) {
        String filePath = file.getPath();
        long now = lineStats.updatedAt != null ? lineStats.updatedAt : System.currentTimeMillis();
        LineStats previous = WakaTime.lineStatsCache.get(filePath);
        if (previous == null || previous.lineCount == null) {
            return;
        }

        int delta = lineStats.lineCount - previous.lineCount;

        // prevent counting large copy/paste as human typed lines of code
        if (delta > 50 && previous.updatedAt != null && Math.abs(now - previous.updatedAt) < 60000) {
            delta = 0;
        }

        if (delta == 0) return;

        Integer current = WakaTime.humanLineChanges.get(filePath);
        WakaTime.humanLineChanges.put(filePath, (current != null ? current : 0) + delta);
    }

    private static synchronized Integer popHumanLineChanges(@NotNull String filePath) {
        Integer lineChanges = WakaTime.humanLineChanges.remove(filePath);
        Boolean hasHumanTyping = WakaTime.filesWithHumanTyping.remove(filePath);
        if (!Boolean.TRUE.equals(hasHumanTyping)) return 0;
        return lineChanges;
    }

    public static synchronized void markFileWithHumanTyping(@NotNull VirtualFile file) {
        WakaTime.filesWithHumanTyping.put(file.getPath(), true);
        WakaTime.lastHumanTypingAt.put(file.getPath(), System.currentTimeMillis());
    }

    public static void openDashboardWebsite() {
        BrowserUtil.browse(ConfigFile.getDashboardUrl());
    }

    private static String todayText = "initialized";
    private static BigDecimal todayTextTime = new BigDecimal(0);

    public static String getStatusBarText() {
        if (!WakaTime.READY) return "";
        if (!WakaTime.STATUS_BAR) return "";
        return todayText;
    }

    public static void updateStatusBarText() {

        // rate limit, to prevent from fetching Today's stats too frequently
        BigDecimal now = getCurrentTimestamp();
        if (todayTextTime.add(new BigDecimal(60)).compareTo(now) > 0) return;
        todayTextTime = getCurrentTimestamp();

        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            public void run() {
                ArrayList<String> cmds = new ArrayList<String>();
                cmds.add(Dependencies.getCLILocation());
                cmds.add("--today");

                String apiKey = ConfigFile.getApiKey();
                if (!apiKey.equals("")) {
                    cmds.add("--key");
                    cmds.add(apiKey);
                }

                log.debug("Executing CLI: " + Arrays.toString(obfuscateKey(cmds.toArray(new String[cmds.size()]))));

                try {
                    Process proc = Runtime.getRuntime().exec(cmds.toArray(new String[cmds.size()]));
                    BufferedReader stdout = new BufferedReader(new
                            InputStreamReader(proc.getInputStream()));
                    BufferedReader stderr = new BufferedReader(new
                            InputStreamReader(proc.getErrorStream()));
                    proc.waitFor();
                    ArrayList<String> output = new ArrayList<String>();
                    String s;
                    while ((s = stdout.readLine()) != null) {
                        output.add(s);
                    }
                    while ((s = stderr.readLine()) != null) {
                        output.add(s);
                    }
                    log.debug("Command finished with return value: " + proc.exitValue());
                    todayText = " " + String.join("", output);
                    todayTextTime = getCurrentTimestamp();
                } catch (InterruptedException interruptedException) {
                    warnException(interruptedException);
                } catch (Exception e) {
                    warnException(e);
                    if (Dependencies.isWindows() && e.toString().contains("Access is denied")) {
                        try {
                            Messages.showWarningDialog("Microsoft Defender is blocking WakaTime. Please allow " + Dependencies.getCLILocation() + " to run so WakaTime can upload code stats to your dashboard.", "Error");
                        } catch (Exception ex) { }
                    }
                }
            }
        });
    }

    private static String obfuscateKey(String key) {
        String newKey = null;
        if (key != null) {
            newKey = key;
            if (key.length() > 4)
                newKey = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXX" + key.substring(key.length() - 4);
        }
        return newKey;
    }

    private static String[] obfuscateKey(String[] cmds) {
        ArrayList<String> newCmds = new ArrayList<String>();
        String lastCmd = "";
        for (String cmd : cmds) {
            if (lastCmd == "--key")
                newCmds.add(obfuscateKey(cmd));
            else
                newCmds.add(cmd);
            lastCmd = cmd;
        }
        return newCmds.toArray(new String[newCmds.size()]);
    }

    public static void logTrackedActivityEvent(@NotNull String event, @Nullable String activityKey, @Nullable String category, @Nullable Project project, @Nullable String details) {
        if (!log.isDebugEnabled()) return;
        if (!shouldLogTrackedActivity(category)) return;
        StringBuilder message = new StringBuilder("tracked-activity ");
        message.append(event);
        message.append(" category=").append(safeValue(category));
        if (activityKey != null) {
            message.append(" key=").append(activityKey);
        }
        if (project != null && !project.isDisposed()) {
            message.append(" project=").append(project.getName());
        }
        if (details != null && !details.trim().isEmpty()) {
            message.append(" ").append(details);
        }
        log.debug(message.toString());
    }

    private static void logTrackedActivityHeartbeat(@NotNull String event, @Nullable String category, @Nullable Project project, @Nullable VirtualFile file, boolean force, @Nullable LineStats lineStats) {
        if (!log.isDebugEnabled()) return;
        if (!shouldLogTrackedActivity(category)) return;
        StringBuilder message = new StringBuilder("tracked-activity ");
        message.append(event);
        message.append(" category=").append(safeValue(category));
        if (project != null && !project.isDisposed()) {
            message.append(" project=").append(project.getName());
        }
        if (file != null) {
            message.append(" file=").append(file.getPath());
        }
        message.append(" force=").append(force);
        if (lineStats != null) {
            if (lineStats.lineNumber != null) {
                message.append(" line=").append(lineStats.lineNumber);
            }
            if (lineStats.cursorPosition != null) {
                message.append(" cursor=").append(lineStats.cursorPosition);
            }
        }
        log.debug(message.toString());
    }

    private static boolean shouldLogTrackedActivity(@Nullable String category) {
        return CATEGORY_BUILDING.equals(category) ||
               CATEGORY_DEBUGGING.equals(category) ||
               CATEGORY_RUNNING_TESTS.equals(category);
    }

    @NotNull
    private static String safeValue(@Nullable String value) {
        return value == null ? "<none>" : value;
    }

    public static void debugException(Exception e) {
        if (!log.isDebugEnabled()) return;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String str = e.getMessage() + "\n" + sw.toString();
        log.debug(str);
    }

    public static void warnException(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String str = e.getMessage() + "\n" + sw.toString();
        log.warn(str);
    }

    public static void errorException(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String str = e.getMessage() + "\n" + sw.toString();
        log.error(str);
    }

    @NotNull
    public String getComponentName() {
        return "WakaTime";
    }
}
