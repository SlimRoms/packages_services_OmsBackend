package com.slimroms.omsbackend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.slimroms.themecore.BaseThemeHelper;
import com.slimroms.themecore.BaseThemeService;
import com.slimroms.themecore.Overlay;
import com.slimroms.themecore.OverlayFlavor;
import com.slimroms.themecore.OverlayGroup;
import com.slimroms.themecore.OverlayThemeInfo;
import com.slimroms.themecore.Theme;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

public class OmsBackendService extends BaseThemeService {
    private HashMap<String, String> mSystemUIPackages = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemUIPackages.put("com.android.systemui.headers", "System UI Headers");
        mSystemUIPackages.put("com.android.systemui.navbars", "System UI Navigation");
        mSystemUIPackages.put("com.android.systemui.statusbars", "System UI Status Bar Icons");
        mSystemUIPackages.put("com.android.systemui.tiles", "System UI QS Tile Icons");
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
                try {
                    Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        info.groups.put(OverlayGroup.OVERLAYS, getOverlays(themeContext, olays));
                    }
                    String[] fonts = themeContext.getAssets().list("fonts");
                    if (fonts.length > 0) {
                        OverlayGroup fontGroup = new OverlayGroup("Fonts");
                        for (String font : fonts) {
                            fontGroup.overlays.add(new Overlay(font, "systemui", false));
                        }
                        info.groups.put(OverlayGroup.FONTS, fontGroup);
                    }
                    String[] bootanis = themeContext.getAssets().list("bootanimation");
                    if (bootanis.length > 0) {
                        OverlayGroup bootanimations = new OverlayGroup("Bootanimations");
                        for (String bootani : bootanis) {
                            bootanimations.overlays.add(new Overlay(bootani, "systemui", false));
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
            try {
                File themeCache = setupCache(theme.packageName);
                Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                OverlayGroup overlays = info.groups.get(OverlayGroup.OVERLAYS);
                for (Overlay overlay : overlays.overlays) {
                    File overlayFolder = new File(themeCache, overlay.targetPackage);
                    copyAssetFolder(themeContext.getAssets(), "overlays/" + overlay.targetPackage, overlayFolder.getAbsolutePath() + "/res");
                }
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

    private OverlayGroup getOverlays(Context themeContext, String[] packages) {
        OverlayGroup group = new OverlayGroup("Overlays");
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
                try {
                    Drawable d = getPackageManager().getApplicationIcon(themeContext.getPackageName());
                    overlay.overlayImage = ((BitmapDrawable) d).getBitmap();
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                loadOverlayFlavors(themeContext, overlay);
                group.overlays.add(overlay);
            }
        }
        getThemeStyles(themeContext, group);
        return group;
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
            overlay.flavors.addAll(flavorMap.values());
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
}
