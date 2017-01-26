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
import android.util.Log;

import com.slimroms.themecore.BaseThemeHelper;
import com.slimroms.themecore.BaseThemeService;
import com.slimroms.themecore.Overlay;
import com.slimroms.themecore.OverlayGroup;
import com.slimroms.themecore.OverlayThemeInfo;
import com.slimroms.themecore.Theme;

import java.io.IOException;
import java.util.List;

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
                    Log.d("TEST", "pn=" + info.packageName);
                    String name = info.metaData.getString("Substratum_Name");
                    String author = info.metaData.getString("Substratum_Author");
                    Drawable d = info.loadIcon(pm);
                    Bitmap icon = ((BitmapDrawable) d).getBitmap();
                    if (!TextUtils.isEmpty(name)) {
                        try {
                            PackageInfo pInfo = pm.getPackageInfo(info.packageName, 0);
                            themes.add(createTheme(name, info.packageName,
                                    Integer.toString(pInfo.versionCode), author, icon));
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return themes.size();
        }

        @Override
        public void getThemeContent(Theme theme, OverlayThemeInfo info) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                try {
                    Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                    OverlayGroup overlays = new OverlayGroup("Overlays");
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        for (String targetPackage : olays) {
                            try {
                                ApplicationInfo targetInfo = pm.getApplicationInfo(targetPackage, 0);
                                overlays.overlays.add(new Overlay((String) targetInfo.loadLabel(pm), targetPackage, true));
                            } catch (PackageManager.NameNotFoundException e) {
                                overlays.overlays.add(new Overlay(targetPackage, targetPackage, false));
                            }
                        }
                        info.groups.add(overlays);
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
}
