package com.slimroms.omsbackend;

import android.content.Context;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kellinwood.security.zipsigner.ZipSigner;

import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

public class OmsBackendService extends BaseThemeService {
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
        Log.d("TEST", "getThemeHelper");
        return new Helper();
    }

    @Override
    protected String getThemeType() {
        return "oms";
    }

    private final class Helper extends BaseThemeHelper {

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
        public void getThemeContent(Theme theme, OverlayThemeInfo info) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                SharedPreferences prefs = getSharedPreferences(theme.packageName + "_prefs", 0);
                try {
                    Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        info.groups.put(OverlayGroup.OVERLAYS, getOverlays(themeContext, olays, prefs));
                    }
                    String[] fonts = themeContext.getAssets().list("fonts");
                    if (fonts.length > 0) {
                        OverlayGroup fontGroup = new OverlayGroup();
                        for (String font : fonts) {
                            fontGroup.overlays.add(new Overlay(font, "", false));
                        }
                        info.groups.put(OverlayGroup.FONTS, fontGroup);
                    }
                    String[] bootanis = themeContext.getAssets().list("bootanimation");
                    if (bootanis.length > 0) {
                        OverlayGroup bootanimations = new OverlayGroup();
                        for (String bootani : bootanis) {
                            bootanimations.overlays.add(new Overlay(bootani, "", false));
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
        public boolean installOverlaysFromTheme(Theme theme, OverlayThemeInfo info) throws RemoteException {
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
                            + overlay.targetPackage + "/res", overlayFolder.getAbsolutePath() + "/res");
                    // TODO: type 3 overlays
                    Log.d("TEST", "theme style=" + overlays.selectedStyle);
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
                    compileOverlay(theme, overlay, overlayFolder.getAbsolutePath());
                    installAndEnable(getCacheDir().getAbsolutePath() + "/" + theme.packageName + "/overlays/" + theme.packageName + "." + overlay.targetPackage + ".apk", theme.packageName + "." + overlay.targetPackage);
                }
                edit.apply();
                mOverlayManager.refresh(UserHandle.USER_CURRENT);
                notifyInstallComplete();
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public boolean uninstallOverlays(OverlayThemeInfo info) throws RemoteException {
            return false;
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
        try {
            String manifestContent = IOUtils.toString(getBaseContext().getAssets().open("AndroidManifest.xml"))
                    .replace("<<TARGET_PACKAGE>>", targetPackage)
                    .replace("<<PACKAGE_NAME>>", theme.packageName + "." + overlay.targetPackage);
            FileUtils.writeStringToFile(new File(path, "AndroidManifest.xml"), manifestContent);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void compileOverlay(Theme theme, Overlay overlay, String overlayPath) {
        File overlayFolder = new File(getCacheDir() + "/" + theme.packageName + "/overlays");
        if (!overlayFolder.exists()) {
            overlayFolder.mkdirs();
        }
        try {
            Process nativeApp = Runtime.getRuntime().exec(new String[]{
                    getAapt(), "p",
                    "-M", overlayPath + "/AndroidManifest.xml",
                    "-S", overlayPath + "/res",
                    "-I", "/system/framework/framework-res.apk",
                    "-F", overlayFolder.getAbsolutePath() + "/" + theme.packageName + "." + overlay.targetPackage + "_unsigned.apk"
            });
            nativeApp.waitFor();
            Log.d("TEST-ERROR", "e=" + IOUtils.toString(nativeApp.getErrorStream()));
            Log.d("TEST-OUT", "o=" + IOUtils.toString(nativeApp.getInputStream()));
            // sign
            ZipSigner zipSigner = new ZipSigner();
            zipSigner.setKeymode("testkey");
            zipSigner.signZip(overlayFolder.getAbsolutePath() + "/" + theme.packageName + "." + overlay.targetPackage + "_unsigned.apk",
                overlayFolder.getAbsolutePath() + "/" + theme.packageName + "." + overlay.targetPackage + ".apk");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getAapt() {
        String path = getFilesDir().getAbsolutePath() + "/aapt";
        if (new File(path).exists()) {
            Log.d("TEST", "found aapt");
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
                Log.e("Error, cmd: " + cmd, error);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void installAndEnable(String apk, String packageName) {
        try {
            if (mPMUtils.installPackage(apk)) {
                mOverlayManager.setEnabled(packageName, true, UserHandle.USER_CURRENT, true);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private OverlayGroup getOverlays(Context themeContext, String[] packages, SharedPreferences prefs) {
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
                overlay = new Overlay(getSystemUIOvelayName(p), p, true);
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
                        if (oi.packageName.equals(themeContext.getPackageName() + "." + overlay.targetPackage)) {
                            overlay.checked = true;
                        }
                    }
                }
                try {
                    Drawable d = getPackageManager().getApplicationIcon(getTargetPackage(overlay.targetPackage));
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
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
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
                Log.d("TEST", "flavor=" + flavor);
                if (flavor.contains("res")
                        || flavor.contains("type3")) {
                    continue;
                }
                if (flavor.startsWith("type")) {
                    if (!flavor.contains("_")) {
                        try {
                            String flavorName = IOUtils.toString(themeContext.getAssets().open(
                                    "overlays/" + overlay.targetPackage + "/" + flavor));
                            Log.d("TEST", "flavorName=" + flavorName);
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
                        Log.d("TEST", "key=" + key);
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

    private String getSystemUIOvelayName(String pName) {
        return mSystemUIPackages.get(pName);
    }

    private boolean isSystemUIOverlay(String pName) {
       return mSystemUIPackages.containsKey(pName);
    }

    private File setupCache(String packageName) {
        File cache = new File(getCacheDir(), packageName);
        if (!cache.exists()) {
            if (!cache.mkdirs()) {
                Log.e("OmsBackendService", "unable to create directory : "
                        + cache.getAbsolutePath());
            }
        }
        return cache;
    }

    private boolean copyAssetFolder(AssetManager am, String assetPath, String path) {

        Log.d("TEST", "copyAssetFolder: fromFolder=" + assetPath + " : toFolder=" + path);

        try {
            String[] files = am.list(assetPath);
            if (!new File(path).exists() && !new File(path).mkdirs()) {
                throw new RuntimeException("cannot create directory: " + path);
            }
            boolean res = true;
            for (String file : files) {
                Log.d("TEST", "file=" + file);
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

        Log.d("TEST", "copyAsset: from=" + fromAssetPath + " : to=" + toPath);

        File parent = new File(toPath).getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            Log.d("OmsBackendService", "Unable to create " + parent.getAbsolutePath());
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
