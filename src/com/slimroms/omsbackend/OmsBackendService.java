package com.slimroms.omsbackend;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.slimroms.themecore.BaseThemeHelper;
import com.slimroms.themecore.BaseThemeService;
import com.slimroms.themecore.Overlay;
import com.slimroms.themecore.OverlayFlavor;
import com.slimroms.themecore.OverlayGroup;
import com.slimroms.themecore.OverlayThemeInfo;
import com.slimroms.themecore.Theme;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kellinwood.security.zipsigner.ZipSigner;

import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

public class OmsBackendService extends BaseThemeService {

    private static final String TAG = "OmsBackendService";

    private static final String BOOTANIMATION_CACHED_SUFFIX = "_bootanimation.zip";

    private HashMap<String, String> mSystemUIPackages = new HashMap<>();

    private PackageManagerUtils mPMUtils;
    private IOverlayManager mOverlayManager;

    private Map<String, List<OverlayInfo>> mOverlays = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemUIPackages.put("com.android.systemui.headers", "System UI Headers");
        mSystemUIPackages.put("com.android.systemui.navbars", "System UI Navigation");
        mSystemUIPackages.put("com.android.systemui.statusbars", "System UI Status Bar Icons");
        mSystemUIPackages.put("com.android.systemui.tiles", "System UI QS Tile Icons");

        mOverlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"));
    }

    @Override
    public BaseThemeHelper getThemeHelper() {
        return new Helper();
    }

    @Override
    protected String getThemeType() {
        return "oms";
    }

    private final class Helper extends BaseThemeHelper {

        @Override
        public String getBackendTitle() {
            return "OmsBackend";
        }

        @Override
        public int getThemePackages(List<Theme> themes) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                List<ApplicationInfo> apps =
                        pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo info : apps) {
                    if (info.metaData == null) {
                        continue;
                    }
                    String name = info.metaData.getString("Substratum_Name");
                    String author = info.metaData.getString("Substratum_Author");
                    Drawable d = info.loadIcon(pm);
                    Bitmap icon = ((BitmapDrawable) d).getBitmap();
                    if (!TextUtils.isEmpty(name)) {
                        try {
                            PackageInfo pInfo = pm.getPackageInfo(info.packageName, 0);
                            Theme theme = createTheme(name, info.packageName,
                                    Integer.toString(pInfo.versionCode), author, icon);
                            themes.add(theme);
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return themes.size();
        }

        @Override
        public Theme getThemeByPackage(String packageName) {
            return getTheme(packageName);
        }

        @Override
        public void getInstalledOverlays(OverlayGroup group) throws RemoteException {
            Map<String, List<OverlayInfo>> overlayInfos = new HashMap<>();
            try {
                overlayInfos = mOverlayManager.getAllOverlays(UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            for (List<OverlayInfo> overlays : overlayInfos.values()) {
                for (OverlayInfo overlayInfo : overlays) {
                    Overlay overlay = null;
                    ApplicationInfo info = null;
                    try {
                        info = getPackageManager().getApplicationInfo(overlayInfo.packageName, PackageManager.GET_META_DATA);
                    } catch (PackageManager.NameNotFoundException e) {
                        continue;
                    }
                    if (info.metaData == null) {
                        Log.e(TAG, "overlay is missing metaData");
                        continue;
                    }
                    String targetPackage = info.metaData.getString("target_package",
                            overlayInfo.targetPackageName);
                    if (isSystemUIOverlay(targetPackage)) {
                        overlay = new Overlay(getSystemUIOverlayName(targetPackage),
                                targetPackage, true);
                    } else {
                        overlay = new Overlay((String) info.loadLabel(getPackageManager()),
                                targetPackage, true);
                    }
                    if (overlay != null) {
                        overlay.overlayVersion = info.metaData.getFloat("theme_version", 1.0f);
                        overlay.themePackage = info.metaData.getString("theme_package", null);
                        overlay.isOverlayInstalled = true;
                        try {
                            Drawable d = getPackageManager().getApplicationIcon(
                                    getTargetPackage(overlay.targetPackage));
                            overlay.overlayImage = drawableToBitmap(d);
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                        group.overlays.add(overlay);
                    }
                }
            }
        }

        @Override
        public void getThemeContent(Theme theme, OverlayThemeInfo info) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                SharedPreferences prefs = getSharedPreferences(theme.packageName + "_prefs", 0);
                try {
                    Context themeContext =
                            getBaseContext().createPackageContext(theme.packageName, 0);
                    Drawable d = getPackageManager().getApplicationIcon(theme.packageName);
                    Bitmap icon = drawableToBitmap(d);
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        info.groups.put(OverlayGroup.OVERLAYS,
                                getOverlays(themeContext, olays, prefs));
                    }
                    String[] fonts = themeContext.getAssets().list("fonts");
                    if (fonts.length > 0) {
                        OverlayGroup fontGroup = new OverlayGroup();
                        for (String font : fonts) {
                            Overlay fon = new Overlay(font, font, true);
                            fon.overlayImage = icon;
                            fontGroup.overlays.add(fon);
                        }
                        info.groups.put(OverlayGroup.FONTS, fontGroup);
                    }
                    String[] bootanis = themeContext.getAssets().list("bootanimation");
                    if (bootanis.length > 0) {
                        OverlayGroup bootanimations = new OverlayGroup();
                        for (String bootani : bootanis) {
                            // cache bootanimation for further preview
                            File bootanimFile = new File(getBaseContext().getCacheDir(),
                                    theme.packageName + bootani + BOOTANIMATION_CACHED_SUFFIX);
                            if (bootanimFile.exists()) {
                                bootanimFile.delete();
                            }
                            InputStream is = themeContext.getAssets().open("bootanimation/" + bootani);
                            try {
                                FileUtils.copyInputStreamToFile(is, bootanimFile);
                            }
                            catch (IOException e) {
                                e.printStackTrace();
                            }
                            finally {
                                is.close();
                            }

                            Overlay bootanimation = new Overlay(bootani, bootani, true);
                            bootanimation.tag = bootanimFile.getAbsolutePath();
                            bootanimation.overlayImage = icon;
                            bootanimations.overlays.add(bootanimation);
                        }
                        info.groups.put(OverlayGroup.BOOTANIMATIONS, bootanimations);
                    }
                } catch (PackageManager.NameNotFoundException|IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public int checkPermissions() throws RemoteException {
            return 0;
        }

        @Override
        public boolean installOverlaysFromTheme(Theme theme, OverlayThemeInfo info)
                throws RemoteException {
            if (mPMUtils == null) {
                mPMUtils = new PackageManagerUtils(getBaseContext());
            }
            try {
                File themeCache = setupCache(theme.packageName);
                Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                OverlayGroup overlays = info.groups.get(OverlayGroup.OVERLAYS);
                SharedPreferences prefs = getSharedPreferences(theme.packageName + "_prefs", 0);
                SharedPreferences.Editor edit = prefs.edit();
                if (!TextUtils.isEmpty(overlays.selectedStyle)) {
                    edit.putString("selectedStyle", overlays.selectedStyle);
                }
                for (Overlay overlay : overlays.overlays) {
                    if (!overlay.checked) continue;
                    notifyInstallProgress(overlays.overlays.size(),
                            overlays.overlays.indexOf(overlay));
                    File overlayFolder = new File(themeCache, overlay.targetPackage);
                    copyAssetFolder(themeContext.getAssets(), "overlays/"
                            + overlay.targetPackage + "/res",
                            overlayFolder.getAbsolutePath() + "/res");
                    if (!TextUtils.isEmpty(overlays.selectedStyle)) {
                        copyAssetFolder(themeContext.getAssets(), "overlays/"
                                + overlay.targetPackage + "/" + overlays.selectedStyle,
                                overlayFolder.getAbsolutePath() + "/res");
                    }

                    // handle type 2 overlay if non-default selected
                    OverlayFlavor type2 = overlay.flavors.get("type2");
                    if (type2 != null) {
                        copyAssetFolder(themeContext.getAssets(), "overlays/"
                                + overlay.targetPackage + "/" + type2.selected,
                                overlayFolder.getAbsolutePath() + "/res");
                        edit.putString(overlay.targetPackage + "_type2", type2.selected);
                    }

                    // handle type1 last
                    handleExtractType1Flavor(themeContext, overlay, "type1a", overlayFolder, edit);
                    handleExtractType1Flavor(themeContext, overlay, "type1b", overlayFolder, edit);
                    handleExtractType1Flavor(themeContext, overlay, "type1c", overlayFolder, edit);

                    generateManifest(theme, overlay, overlayFolder.getAbsolutePath());
                    if (!compileOverlay(theme, overlay, overlayFolder.getAbsolutePath())) {
                        continue;
                    }
                    installAndEnable(getCacheDir().getAbsolutePath() + "/" + theme.packageName +
                            "/overlays/" + theme.packageName + "." + overlay.targetPackage +
                            ".apk", theme.packageName + "." + overlay.targetPackage);
                }
                edit.apply();
                //mOverlayManager.refresh(UserHandle.USER_CURRENT);
                sendBroadcast(new Intent("slim.action.INSTALL_FINISHED"));
                notifyInstallComplete();
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public boolean uninstallOverlays(OverlayGroup group) throws RemoteException {
            List<Overlay> overlays = new ArrayList<>();
            for (Overlay overlay : group.overlays) {
                if (!overlay.checked) {
                    overlays.add(overlay);
                }
            }
            if (overlays == null || overlays.isEmpty()) return false;

            if (mPMUtils == null) {
                mPMUtils = new PackageManagerUtils(getBaseContext());
            }

            notifyUninstallProgress(overlays.size(), 0);

            Map<String, List<OverlayInfo>> overlayInfos = new HashMap<>();
            try {
                overlayInfos = mOverlayManager.getAllOverlays(UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            for (Overlay overlay : overlays) {
                String packageName = overlay.themePackage + "." + overlay.targetPackage;
                List<OverlayInfo> ois = overlayInfos.get(getTargetPackage(overlay.targetPackage));
                if (ois != null) {
                    for (OverlayInfo oi : ois) {
                        if (oi.packageName.equals(packageName)) {
                            notifyUninstallProgress(overlays.size(), overlays.indexOf(overlay));
                            mOverlayManager.setEnabled(packageName,
                                    false, UserHandle.USER_CURRENT, false);
                            mPMUtils.uninstallPackage(packageName);
                        }
                    }
                }
            }
            sendBroadcast(new Intent("slim.action.INSTALL_FINISHED"));
            notifyUninstallComplete();
            return true;
        }

        @Override
        public boolean isRebootRequired() throws RemoteException {
            return false;
        }

        @Override
        public void reboot() throws RemoteException {

        }

        @Override
        public boolean isAvailable() throws RemoteException {
            return true;
        }
    }

    private void generateManifest(Theme theme, Overlay overlay, String path) {
        String targetPackage = overlay.targetPackage;
        if (mSystemUIPackages.containsKey(targetPackage)) {
            targetPackage = "com.android.systemui";
        }
        StringBuilder manifest = new StringBuilder();
        manifest.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        manifest.append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
        manifest.append("package=\"" + theme.packageName + "." + overlay.targetPackage + "\">\n");
        manifest.append("<overlay\n");
        manifest.append("android:targetPackage=\"" + targetPackage + "\"/>\n");
        manifest.append("<application>\n");
        manifest.append("<meta-data android:name=\"theme_version\" android:value=\""
                + theme.themeVersion + "\"/>\n");
        manifest.append("<meta-data android:name=\"theme_package\" android:value=\""
                + theme.packageName + "\"/>\n");
        manifest.append("<meta-data android:name=\"target_package\" android:value=\""
                + overlay.targetPackage + "\"/>\n");
        manifest.append("</application>\n");
        manifest.append("</manifest>");
        try {
            FileUtils.writeStringToFile(new File(path, "AndroidManifest.xml"), manifest.toString());
        } catch (IOException e) {
        }
    }

    private boolean compileOverlay(Theme theme, Overlay overlay, String overlayPath) {
        File overlayFolder = new File(getCacheDir() + "/" + theme.packageName + "/overlays");
        if (!overlayFolder.exists()) {
            overlayFolder.mkdirs();
        }
        File unsignedOverlay = new File(overlayFolder,
                theme.packageName + "." + overlay.targetPackage + "_unsigned.apk");
        File signedOverlay = new File(overlayFolder,
                theme.packageName + "." + overlay.targetPackage + ".apk");
        if (unsignedOverlay.exists()) {
            unsignedOverlay.delete();
        }
        if (signedOverlay.exists()) {
            signedOverlay.delete();
        }
        try {
            Process nativeApp = Runtime.getRuntime().exec(new String[]{
                    getAapt(), "p",
                    "-M", overlayPath + "/AndroidManifest.xml",
                    "-S", overlayPath + "/res",
                    "-I", "/system/framework/framework-res.apk",
                    "-F", unsignedOverlay.getAbsolutePath()
            });
            nativeApp.waitFor();
            int exitCode = nativeApp.exitValue();
            String error = IOUtils.toString(nativeApp.getErrorStream());
            if (exitCode != 0 || !TextUtils.isEmpty(error)) {
                Log.e(TAG, "aapt: exitCode:" + exitCode + " error: " + error);
                return false;
            }
            // sign
            if (unsignedOverlay.exists()) {
                ZipSigner zipSigner = new ZipSigner();
                zipSigner.setKeymode("testkey");
                zipSigner.signZip(unsignedOverlay.getAbsolutePath(),
                    signedOverlay.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create overlay - unable to compile "
                        + unsignedOverlay.getAbsolutePath());
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private String getAapt() {
        String path = getFilesDir().getAbsolutePath() + "/aapt";
        if (new File(path).exists()) {
        } else {
            try {
                copyInputStreamToFile(getAssets().open("aapt"), new File(path));
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        runCommand("chmod 777 " + path);
        return path;
    }

    public static void runCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec("sh");
            DataOutputStream os = new DataOutputStream(
                    process.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();

            int exitCode = process.waitFor();
            String output = IOUtils.toString(process.getInputStream());
            String error = IOUtils.toString(process.getErrorStream());
            if (exitCode != 0 || (!"".equals(error) && null != error)) {
                Log.e(TAG,  "cmd: " + cmd + " exitCode:" + exitCode + " error: " + error);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void installAndEnable(String apk, String packageName) {
        try {
            if (mPMUtils.installPackage(apk)) {
                if (!mOverlayManager.setEnabled(packageName,
                        true, UserHandle.USER_CURRENT, false)) {
                    Log.e(TAG, "Failed to enable overlay - " + packageName);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private OverlayGroup getOverlays(Context themeContext,
            String[] packages, SharedPreferences prefs) {
        OverlayGroup group = new OverlayGroup();

        Map<String, List<OverlayInfo>> overlays = new HashMap<>();
        try {
            overlays = mOverlayManager.getAllOverlays(UserHandle.USER_CURRENT);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        group.selectedStyle = prefs.getString("selectedStyle", "");

        for (String p : packages) {
            Overlay overlay = null;
            if (isSystemUIOverlay(p)) {
                overlay = new Overlay(getSystemUIOverlayName(p), p, true);
            } else {
                try {
                    ApplicationInfo info = getPackageManager().getApplicationInfo(p, 0);
                    overlay = new Overlay((String) info.loadLabel(getPackageManager()), p, true);
                } catch (PackageManager.NameNotFoundException e) {
                    //overlay = new Overlay(p, p, false);
                }
            }
            if (overlay != null) {
                List<OverlayInfo> ois = overlays.get(getTargetPackage(overlay.targetPackage));
                if (ois != null) {
                    for (OverlayInfo oi : ois) {
                        if (oi.packageName.equals(themeContext.getPackageName() +
                                "." + overlay.targetPackage) && oi.state == OverlayInfo.STATE_APPROVED_ENABLED) {
                            overlay.checked = true;
                        }
                    }
                }
                try {
                    Drawable d = getPackageManager().getApplicationIcon(
                            getTargetPackage(overlay.targetPackage));
                    overlay.overlayImage = drawableToBitmap(d);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                loadOverlayFlavors(themeContext, overlay);
                for (OverlayFlavor flavor : overlay.flavors.values()) {
                    String sel = prefs.getString(overlay.targetPackage + "_" + flavor.key, "");
                    if (!TextUtils.isEmpty(sel)) {
                        flavor.selected = sel;
                    }
                }
                group.overlays.add(overlay);
            }
        }
        getThemeStyles(themeContext, group);
        return group;
    }

    private String getTargetPackage(String targetPackage) {
        if (mSystemUIPackages.containsKey(targetPackage)) {
            return "com.android.systemui";
        }
        return targetPackage;
    }

    public static Bitmap drawableToBitmap (Drawable drawable) {
        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if(bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        if(drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            // Single color bitmap will be created of 1x1 pixel
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void loadOverlayFlavors(Context themeContext, Overlay overlay) {
        String[] types = null;
        try {
            types = themeContext.getAssets().list("overlays/" + overlay.targetPackage);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (types != null) {
            Map<String, OverlayFlavor> flavorMap = new HashMap<>();
            for (String flavor : types) {
                if (flavor.contains("res")
                        || flavor.contains("type3")) {
                    continue;
                }
                if (flavor.startsWith("type")) {
                    if (!flavor.contains("_")) {
                        try {
                            String flavorName = IOUtils.toString(themeContext.getAssets().open(
                                    "overlays/" + overlay.targetPackage + "/" + flavor));
                            flavorMap.put(flavor, new OverlayFlavor(flavorName, flavor));
                        } catch (IOException e) {
                            // ignore
                        }
                    } else {
                        String flavorName = flavor.substring(flavor.indexOf("_") + 1);
                        if (flavorName.contains(".")) {
                            flavorName = flavorName.substring(0, flavorName.indexOf("."));
                        }
                        String key = flavor.substring(0, flavor.indexOf("_"));
                        if (flavorMap.containsKey(key)) {
                            flavorMap.get(key).flavors.put(flavor, flavorName);
                        }
                    }
                }
            }
            overlay.flavors.putAll(flavorMap);
        }
    }

    private void getThemeStyles(Context themeContext, OverlayGroup group) {
        String[] types = null;
        try {
            types = themeContext.getAssets().list("overlays/android");
            String def = IOUtils.toString(themeContext.getAssets().open("overlays/android/"
                    + "type3"));
            group.styles.put("type3", def);
        } catch (IOException e) {
            // ignore
        }
        if (types != null) {
            for (String type : types) {
                if (!type.startsWith("type3")) {
                    continue;
                }
                if (type.contains("_")) {
                    String flavorName = type.substring(type.indexOf("_") + 1);
                    group.styles.put(type, flavorName);
                }
            }
        }
    }

    private String getSystemUIOverlayName(String pName) {
        return mSystemUIPackages.get(pName);
    }

    private boolean isSystemUIOverlay(String pName) {
       return mSystemUIPackages.containsKey(pName);
    }

    private File setupCache(String packageName) {
        File cache = new File(getCacheDir(), packageName);
        if (!cache.exists()) {
            if (!cache.mkdirs()) {
                Log.e(TAG, "unable to create directory : "
                        + cache.getAbsolutePath());
            }
        }
        return cache;
    }

    private boolean copyAssetFolder(AssetManager am, String assetPath, String path) {
        try {
            String[] files = am.list(assetPath);
            if (!new File(path).exists() && !new File(path).mkdirs()) {
                throw new RuntimeException("cannot create directory: " + path);
            }
            boolean res = true;
            for (String file : files) {
                if (am.list(assetPath + "/" + file).length == 0) {
                    res &= copyAsset(am, assetPath + "/" + file, path + "/" + file);
                } else {
                    res &= copyAssetFolder(am, assetPath + "/" + file, path + "/" + file);
                }
            }
            return res;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean copyAsset(AssetManager assetManager,
                                    String fromAssetPath, String toPath) {
        InputStream in;
        File parent = new File(toPath).getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            Log.d(TAG, "Unable to create " + parent.getAbsolutePath());
        }

        try {
            in = assetManager.open(fromAssetPath);
            copyInputStreamToFile(in, new File(toPath));
            in.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void handleExtractType1Flavor(Context themeContext, Overlay overlay, String typeName,
                                          File overlayFolder, SharedPreferences.Editor edit) {
        OverlayFlavor type = overlay.flavors.get(typeName);
        if (type != null) {
            AssetManager am = themeContext.getAssets();
            try {
                String of = "overlays/" + overlay.targetPackage + "/res";
                for (String n : am.list(of)) {
                    if (n.contains("values")) {
                        for (String s : am.list(of + "/" + n)) {
                            if (s.equals(type.key)) {
                                copyAsset(am, "overlays/" + overlay.targetPackage
                                                + "/" + type.selected,
                                        overlayFolder.getAbsolutePath() + "/res/"
                                                + n + "/" + type.key);
                            }
                        }
                    }
                }
                edit.putString(overlay.targetPackage + "_" + typeName, type.selected);
            } catch (IOException e) {}
        }
    }
}
