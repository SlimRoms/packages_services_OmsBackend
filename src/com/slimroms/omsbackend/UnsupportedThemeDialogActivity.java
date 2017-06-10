package com.slimroms.omsbackend;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;

public class UnsupportedThemeDialogActivity extends Activity {

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        final String packageName = getIntent().getStringExtra("package_name");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getIntent().getStringExtra("Substratum_Name"));
        builder.setMessage("Theme is unsupported on this device.");
        builder.setCancelable(false);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                getSystemService(NotificationManager.class)
                        .cancel(101);
                dialog.dismiss();
                finish();
            }
        });
        builder.setNegativeButton("Uninstall", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                getSystemService(NotificationManager.class)
                        .cancel(101);
                dialog.dismiss();
                finish();
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... v) {
                        new PackageManagerUtils(UnsupportedThemeDialogActivity.this)
                                .uninstallPackage(packageName);
                        finish();
                        return null;
                    }
                }.execute();
            }
        });
        builder.show();
    }
}
