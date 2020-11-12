package io.zhile.research.intellij.ier.common;

import com.intellij.ide.Prefs;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.SystemInfo;
import io.zhile.research.intellij.ier.helper.Constants;
import io.zhile.research.intellij.ier.helper.NotificationHelper;
import io.zhile.research.intellij.ier.helper.Reflection;
import org.jdom.Element;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class Resetter {
    private static final String DEFAULT_VENDOR = "jetbrains";
    private static final String OLD_MACHINE_ID_KEY = "JetBrains.UserIdOnMachine";
    private static final String NEW_MACHINE_ID_KEY = DEFAULT_VENDOR + ".user_id_on_machine";
    private static final String DEVICE_ID_KEY = DEFAULT_VENDOR + ".device_id";
    private static final String IDE_EVAL_PREFIX = DEFAULT_VENDOR + "/" + Constants.IDE_NAME_LOWER + "/" + Constants.IDE_HASH;
    private static final String EVAL_KEY = "evlsprt";
    private static final String AUTO_RESET_KEY = Constants.PLUGIN_PREFS_PREFIX + ".auto_reset." + Constants.IDE_NAME_LOWER + "." + Constants.IDE_HASH;

    public static List<EvalRecord> getEvalRecords() {
        List<EvalRecord> list = new ArrayList<>();

        File evalDir = getEvalDir();
        if (evalDir.exists()) {
            File[] files = evalDir.listFiles((dir, name) -> name.endsWith(".key"));
            if (files == null) {
                NotificationHelper.showError(null, "List eval license file failed!");
            } else {
                Arrays.stream(files).forEach(file -> list.add(new LicenseFileRecord(file)));
            }
        }

        Element state = PropertyRecord.PROPS.getState();
        if (state != null) {
            state.getChildren().stream().filter(element -> {
                if (!element.getName().equals("property")) {
                    return false;
                }

                if (element.getAttribute("name") == null || element.getAttribute("value") == null) {
                    return false;
                }

                return element.getAttribute("name").getValue().startsWith(EVAL_KEY);
            }).forEach(element -> list.add(new PropertyRecord(element.getAttribute("name").getValue())));
        }

        PreferenceRecord[] prefsValue = new PreferenceRecord[]{
                new PreferenceRecord(OLD_MACHINE_ID_KEY, true),
                new PreferenceRecord(NEW_MACHINE_ID_KEY),
                new PreferenceRecord(DEVICE_ID_KEY),
        };
        Arrays.stream(prefsValue).filter(record -> record.getValue() != null).forEach(list::add);

        try {
            List<String> prefsList = new ArrayList<>();
            getAllPrefsKeys(Preferences.userRoot().node(IDE_EVAL_PREFIX), prefsList);

            Method methodGetProductCode = Reflection.getMethod(IdeaPluginDescriptor.class, "getProductCode");
            if (null != methodGetProductCode) {
                for (IdeaPluginDescriptor descriptor : PluginManager.getPlugins()) {
                    String productCode = (String) methodGetProductCode.invoke(descriptor);
                    if (null == productCode || productCode.isEmpty()) {
                        continue;
                    }

                    getAllPrefsKeys(Preferences.userRoot().node(DEFAULT_VENDOR + "/" + productCode.toLowerCase()), prefsList);
                }
            }

            prefsList.stream().filter(key -> key.contains(EVAL_KEY)).forEach(key -> {
                if (key.startsWith("/")) {
                    key = key.substring(1).replace('/', '.');
                }
                list.add(new PreferenceRecord(key));
            });
        } catch (Exception e) {
            NotificationHelper.showError(null, "List eval preferences failed!");
        }

        if (SystemInfo.isWindows) {
            for (String name : new String[]{"PermanentUserId", "PermanentDeviceId"}) {
                File file = getSharedFile(name);

                if (null != file && file.exists()) {
                    list.add(new NormalFileRecord(file));
                }
            }
        }

        return list;
    }

    public static void reset(List<EvalRecord> records) {
        records.forEach(Resetter::reset);
    }

    public static void reset(EvalRecord record) {
        try {
            record.reset();
        } catch (Exception e) {
            NotificationHelper.showError(null, e.getMessage());
        }
    }

    public static boolean isAutoReset() {
        return Prefs.getBoolean(AUTO_RESET_KEY, false);
    }

    public static void setAutoReset(boolean isAutoReset) {
        Prefs.putBoolean(AUTO_RESET_KEY, isAutoReset);
        syncPrefs();
    }

    public static void syncPrefs() {
        try {
            Preferences.userRoot().sync();
        } catch (BackingStoreException e) {
            NotificationHelper.showError(null, "Flush preferences failed!");
        }
    }

    protected static File getSharedFile(String fileName) {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }

        return Paths.get(appData, "JetBrains", fileName).toFile();
    }

    protected static File getEvalDir() {
        String configPath = PathManager.getConfigPath();

        return new File(configPath, "eval");
    }

    protected static void getAllPrefsKeys(Preferences prefs, List<String> list) throws BackingStoreException {
        String[] childrenNames = prefs.childrenNames();
        if (childrenNames.length == 0) {
            for (String key : prefs.keys()) {
                list.add(prefs.absolutePath() + "/" + key);
            }
            return;
        }

        for (String childName : childrenNames) {
            getAllPrefsKeys(prefs.node(childName), list);
        }
    }
}