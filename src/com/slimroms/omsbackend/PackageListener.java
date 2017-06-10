package com.slimroms.omsbackend;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Log;

public class PackageListener extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {

        String action = intent.getAction();
        Log.d("TEST", "action - " + action);
        if (TextUtils.isEmpty(action)) return;
        if (intent.getData() == null) return;
        if (!intent.getData().getScheme().equals("package")) return;

        final String packageName = intent.getData().getSchemeSpecificPart();
        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

        // check for unsupported theme
        if (action.equals(Intent.ACTION_PACKAGE_ADDED)) {
            try {
                ApplicationInfo aInfo = context.getPackageManager().getApplicationInfo(packageName,
                        PackageManager.GET_META_DATA);
                if (aInfo.metaData != null && aInfo.metaData.containsKey("Substratum_Name")) {
                    boolean supportsThirdParty =
                            aInfo.metaData.getBoolean("supports_third_party_theme_systems", false);
                    if (!supportsThirdParty) {
                        String message = String.format(context.getString(
                                R.string.theme_unsupported_install_notification),
                                aInfo.metaData.getString("Substratum_Name"));

                        Intent showIntent = new Intent();
                        PendingIntent contentIntent =
                                PendingIntent.getActivity(context, 0, showIntent, 0);

                        Notification.Builder notifBuilder = new Notification.Builder(context);
                        notifBuilder.setContentIntent(contentIntent);
                        notifBuilder.setContentTitle(context.getString(
                                R.string.theme_unsupported_install_notification_title));
                        notifBuilder.setContentText(message);
                        notifBuilder.setSmallIcon(R.drawable.delete);
                        notifBuilder.setPriority(Notification.PRIORITY_MAX);
                        notifBuilder.setVibrate(new long[] { 500 });
                        context.getSystemService(NotificationManager.class)
                                .notify(101, notifBuilder.build());

                        new AsyncTask<Void, Void, Void>() {
                            @Override
                            protected Void doInBackground(Void... v) {
                                new PackageManagerUtils(context).uninstallPackage(packageName);
                                return null;
                            }
                        }.execute();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}
