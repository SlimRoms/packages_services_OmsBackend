package com.slimroms.omsbackend;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.text.TextUtils;

import com.slimroms.themecore.BaseThemeHelper;
import com.slimroms.themecore.BaseThemeService;
import com.slimroms.themecore.Theme;

import java.util.ArrayList;
import java.util.List;

public class OmsBackendService extends BaseThemeService {
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
        public List<Theme> getThemePackages() throws RemoteException {
            List<Theme> themes = new ArrayList<>();
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
                    if (!TextUtils.isEmpty(name)) {
                        try {
                            PackageInfo pInfo = pm.getPackageInfo(info.packageName, 0);
                            themes.add(createTheme(name, info.packageName,
                                    Integer.toString(pInfo.versionCode), author));
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            return themes;
        }

        @Override
        public void getThemeContent(Theme theme) throws RemoteException {

        }

        @Override
        public int checkPermissions() throws RemoteException {
            return 0;
        }

        @Override
        public boolean installOverlaysFromTheme(Theme theme) throws RemoteException {
            return false;
        }

        @Override
        public boolean uninstallOverlays() throws RemoteException {
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
            return false;
        }
    }
}
