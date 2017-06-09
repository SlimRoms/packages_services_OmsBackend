/*
 * Copyright (C) 2017 SlimRoms Project
 * Copyright (C) 2017 Victor Lapin
 * Copyright (C) 2017 Griffin Millender
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.slimroms.omsbackend;

import android.app.ActivityManager;
import android.app.PackageDeleteObserver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.util.SizedInputStream;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public class PackageManagerUtils {

    private static final String TAG = "PackageManagerUtils";

    private Context mContext;
    private IPackageManager mPm;
    private IPackageInstaller mInstaller;

    private static class InstallParams {
        SessionParams sessionParams;
        String installerPackageName = "com.slimroms.omsbackend";
        int userId = UserHandle.USER_ALL;
    }

    public PackageManagerUtils(Context context) {
        mContext = context;
        try {
            mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
            mInstaller = mPm.getPackageInstaller();
        } catch (RemoteException e) {}
    }

    public boolean installPackage(String inPath) throws RemoteException {
        final InstallParams params = makeInstallParams();
        if (params.sessionParams.sizeBytes < 0 && inPath != null) {
            File file = new File(inPath);
            if (file.isFile()) {
                params.sessionParams.setSize(file.length());
            }
        }

        final int sessionId = doCreateSession(params.sessionParams,
                params.installerPackageName, params.userId);

        try {
            if (inPath == null && params.sessionParams.sizeBytes == 0) {
                Log.e(TAG, "Error: must either specify a package size or an APK file");
                return false;
            }
            if (doWriteSession(sessionId, inPath, params.sessionParams.sizeBytes, "base.apk",
                    false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
                return false;
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return false;
            }
            return true;
        } finally {
            try {
                mInstaller.abandonSession(sessionId);
            } catch (Exception ignore) {
            }
        }
    }

    private InstallParams makeInstallParams() {
        final SessionParams sessionParams = new SessionParams(SessionParams.MODE_FULL_INSTALL);
        final InstallParams params = new InstallParams();
        params.sessionParams = sessionParams;
        return params;
    }

    private int doCreateSession(SessionParams params, String installerPackageName, int userId)
            throws RemoteException {
        //userId = translateUserId(userId, "runInstallCreate");
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
            params.installFlags |= PackageManager.INSTALL_ALL_USERS;
        }

        final int sessionId = mInstaller.createSession(params, installerPackageName, userId);
        return sessionId;
    }

    private int doWriteSession(int sessionId, String inPath, long sizeBytes, String splitName,
            boolean logSuccess) throws RemoteException {
        if ("-".equals(inPath)) {
            inPath = null;
        } else if (inPath != null) {
            final File file = new File(inPath);
            if (file.isFile()) {
                sizeBytes = file.length();
            }
        }

        final SessionInfo info = mInstaller.getSessionInfo(sessionId);

        PackageInstaller.Session session = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            session = new PackageInstaller.Session(
                    mInstaller.openSession(sessionId));

            if (inPath != null) {
                in = new FileInputStream(inPath);
            } else {
                in = new SizedInputStream(System.in, sizeBytes);
            }
            out = session.openWrite(splitName, 0, sizeBytes);

            int total = 0;
            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                total += c;
                out.write(buffer, 0, c);

                if (info.sizeBytes > 0) {
                    final float fraction = ((float) c / (float) info.sizeBytes);
                    session.addProgress(fraction);
                }
            }
            session.fsync(out);
            return PackageInstaller.STATUS_SUCCESS;
        } catch (IOException e) {
            Log.e(TAG, "Error: failed to write; " + e.getMessage());
            return PackageInstaller.STATUS_FAILURE;
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(session);
        }
    }

    private int doCommitSession(int sessionId, boolean logSuccess) throws RemoteException {
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInstaller.openSession(sessionId));

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            final Intent result = receiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.e(TAG, "Failure ["
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            }
            return status;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int translateUserId(int userId, String logContext) {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, logContext, "pm command");
    }

    public boolean uninstallPackage(String packageName) {
        LocalIntentReceiver receiver = new LocalIntentReceiver();
        try {
            mInstaller.uninstall(packageName, mContext.getPackageName(), 0,
                    receiver.getIntentSender(), UserHandle.getCallingUserId());
            final Intent intent = receiver.getResult();
            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            return status == PackageInstaller.STATUS_SUCCESS;
        } catch (RemoteException ex) {
            ex.printStackTrace();
        }
        return false;
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
