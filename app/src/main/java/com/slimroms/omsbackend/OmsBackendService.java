package com.slimroms.omsbackend;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.slimroms.themecore.BaseThemeHelper;
import com.slimroms.themecore.BaseThemeService;
import com.slimroms.themecore.Overlay;
import com.slimroms.themecore.OverlayFlavor;
import com.slimroms.themecore.OverlayGroup;
import com.slimroms.themecore.OverlayThemeInfo;
import com.slimroms.themecore.Theme;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OmsBackendService extends BaseThemeService {
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

        private HashMap<String, Theme> mThemes = new HashMap<>();

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
                            if (!mThemes.containsKey(theme.packageName)) {
                                themes.add(theme);
                                mThemes.put(theme.packageName, theme);
                            }
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
            Log.d("TEST", "getThemeByPackage" + " : packageName=" + packageName);
            return mThemes.get(packageName);
        }

        @Override
        public void getThemeContent(Theme theme, OverlayThemeInfo info) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                try {
                    Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        info.groups.add(getOverlays(themeContext, olays));
                    }
                    String[] fonts = themeContext.getAssets().list("fonts");
                    if (fonts.length > 0) {
                        OverlayGroup fontGroup = new OverlayGroup("Fonts");
                        for (String font : fonts) {
                            fontGroup.overlays.add(new Overlay(font, "systemui", false));
                        }
                        info.groups.add(fontGroup);
                    }
                    String[] bootanis = themeContext.getAssets().list("bootanimation");
                    if (bootanis.length > 0) {
                        OverlayGroup bootanimations = new OverlayGroup("Bootanimations");
                        for (String bootani : bootanis) {
                            bootanimations.overlays.add(new Overlay(bootani, "systemui", false));
                        }
                        info.groups.add(bootanimations);
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
        public boolean installOverlaysFromTheme(OverlayThemeInfo info) throws RemoteException {
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
        switch (pName) {
            case "com.android.systemui.headers":
                return "System UI Headers";
            case "com.android.systemui.navbars":
                return "System UI Navigation";
            case "com.android.systemui.statusbars":
                return "System UI Status Bar Icons";
            case "com.android.systemui.tiles":
                return "System UI QS Tile Icons";
        }
        return pName;
    }

    private boolean isSystemUIOverlay(String pName) {
       return pName.equals("com.android.systemui.headers")
                || pName.equals("com.android.systemui.navbars")
                || pName.equals("com.android.systemui.statusbars")
                || pName.equals("com.android.systemui.tiles");
    }
}
