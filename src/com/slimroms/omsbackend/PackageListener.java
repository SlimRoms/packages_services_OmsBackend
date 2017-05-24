package com.slimroms.omsbackend;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Pair;

import com.slimroms.themecore.IThemeService;
import com.slimroms.themecore.Overlay;
import com.slimroms.themecore.OverlayGroup;
import com.slimroms.themecore.OverlayThemeInfo;
import com.slimroms.themecore.Theme;
import com.slimroms.themecore.ThemePrefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by gmillz on 5/24/17.
 */

public class PackageListener extends BroadcastReceiver {

    IThemeService mService;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (TextUtils.isEmpty(action)) return;
        if (intent.getData() == null) return;
        if (!intent.getData().getScheme().equals("package")) return;

        String packageName = intent.getData().getSchemeSpecificPart();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

        List<Theme> t = new ArrayList<>();
        List<Pair<Theme, OverlayThemeInfo>> themes = new ArrayList<>();
        try {
            getService(context).getThemePackages(t);
            for (Theme theme : t) {
                OverlayThemeInfo info = new OverlayThemeInfo();
                getService(context).getThemeContent(theme, info);
                themes.add(new Pair<>(theme, info));
            }
            t.clear();
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        if (!replacing && action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            for (Pair<Theme, OverlayThemeInfo> pair : themes) {
                if (pair.second.groups.containsKey(OverlayGroup.OVERLAYS)) {
                    for (Overlay overlay : pair.second.groups.get(OverlayGroup.OVERLAYS).overlays) {
                        if (overlay.targetPackage.equals(packageName)) {
                            showAppInstalledNotification();
                            break;
                        }
                    }
                }
            }
        }

        if (replacing && action.equals(Intent.ACTION_PACKAGE_REPLACED)) {
            ArrayList<String> themesToUpdate = new ArrayList<>();
            for (File file : new File("/data/system/theme/persistent-cache/" + context.getPackageName()).listFiles()) {
                ThemePrefs prefs = new ThemePrefs(file.getAbsolutePath());
                if (prefs.contains(packageName)) {
                    themesToUpdate.add(file.getName());
                }
            }
        }
    }

    private void showAppInstalledNotification() {
        // ignore for now
    }

    private IThemeService getService(Context context) {
        if (mService != null) return mService;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context, OmsBackendService.class));
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mService = IThemeService.Stub.asInterface(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        while (mService == null) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return mService;
    }
}
