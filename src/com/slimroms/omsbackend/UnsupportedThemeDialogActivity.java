package com.slimroms.omsbackend;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

public class UnsupportedThemeDialogActivity extends Activity {

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        final String packageName = getIntent().getStringExtra("package_name");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getIntent().getStringExtra("theme_name"));
        try {
            builder.setIcon(getPackageManager().getApplicationIcon(packageName));
        } catch (PackageManager.NameNotFoundException ignored) {}
        builder.setMessage("SlimTM compatibility is not currently enabled in this theme, politely ask your themer to enable support.");
        builder.setCancelable(false);

        if (getPackageManager().getInstallerPackageName(packageName).equals("com.android.vending")) {
            builder.setNegativeButton("Go to Play Store", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    getSystemService(NotificationManager.class)
                            .cancel(101);
                    finish();
                    Intent play = new Intent(Intent.ACTION_VIEW);
                    play.setData(Uri.parse("market://details?id=" + packageName));
                    play.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    startActivity(play);
                }
            });
        }
        builder.setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                getSystemService(NotificationManager.class)
                        .cancel(101);
                dialog.dismiss();
                finish();
            }
        });
        builder.show();
    }
}
