/*
 * Copyright (C) 2016-2017 Projekt Substratum
 *
 * Modified/reimplemented for use by SlimRoms :
 *
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

import android.annotation.SuppressLint;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.FontListParser;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.v4.util.Pair;
import android.system.Os;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.slimroms.themecore.*;
import kellinwood.security.zipsigner.ZipSigner;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.*;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

public class OmsBackendService extends BaseThemeService {

    private static final String TAG = "SlimTM-OmsBackendService";

    private static final String BOOTANIMATION_FILE = "/data/system/theme/bootanimation.zip";
    private static final String BOOTANIMATION_METADATA =
            "/data/system/theme/bootanimation-meta.json";

    private static final String THEME_FONT_PATH = "/data/system/theme/fonts";
    private static final String THEME_FONT_METADATA =
            "/data/system/theme/font-meta.json";

    public static final String[] ALLOWED_SOUNDS = {
            "alarm.mp3",
            "alarm.ogg",
            "notification.mp3",
            "notification.ogg",
            "ringtone.mp3",
            "ringtone.ogg",
            "Effect_Tick.mp3",
            "Effect_Tick.ogg",
            "Lock.mp3",
            "Lock.ogg",
            "Unlock.mp3",
            "Unlock.ogg",
    };

    private HashMap<String, String> mSystemUIPackages = new HashMap<>();

    private PackageManagerUtils mPMUtils;
    private IOverlayManager mOverlayManager;
    private ConnectivityManager mConnectivityManager;

    private boolean mReboot = false;

    private Map<String, List<OverlayInfo>> mOverlays = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemUIPackages.put("com.android.systemui.headers", "System UI Headers");
        mSystemUIPackages.put("com.android.systemui.navbars", "System UI Navigation");
        mSystemUIPackages.put("com.android.systemui.statusbars", "System UI Status Bar Icons");
        mSystemUIPackages.put("com.android.systemui.tiles", "System UI QS Tile Icons");

        mOverlayManager = IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"));
        mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

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
        public String getBackendTitle() {
            return "OmsBackend";
        }

        @Override
        public int getThemePackages(List<Theme> themes) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                List<ApplicationInfo> apps =
                        pm.getInstalledApplications(PackageManager.GET_META_DATA);
                for (ApplicationInfo info : apps) {
                    if (!info.enabled || info.metaData == null) {
                        continue;
                    }
                    String name = info.metaData.getString("Substratum_Name");
                    String author = info.metaData.getString("Substratum_Author");
                    if (!TextUtils.isEmpty(name)) {
                        try {
                            PackageInfo pInfo = pm.getPackageInfo(info.packageName, 0);
                            Theme theme = createTheme(name, info.packageName,
                                    pInfo.versionName, pInfo.versionCode, author, null);
                            themes.add(theme);
                        } catch (PackageManager.NameNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            Collections.sort(themes);
            return themes.size();
        }

        @Override
        public Theme getThemeByPackage(String packageName) {
            return getTheme(packageName);
        }

        @Override
        public void getInstalledOverlays(OverlayThemeInfo info) throws RemoteException {
            Map<String, List<OverlayInfo>> overlayInfos = new HashMap<>();
            try {
                overlayInfos = mOverlayManager.getAllOverlays(UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            OverlayGroup overlayGroup = new OverlayGroup();
            for (List<OverlayInfo> overlays : overlayInfos.values()) {
                for (OverlayInfo overlayInfo : overlays) {
                    if (overlayInfo.state != OverlayInfo.STATE_APPROVED_ENABLED)
                        continue;
                    Overlay overlay = null;
                    ApplicationInfo aInfo;
                    try {
                        aInfo = getPackageManager().getApplicationInfo(overlayInfo.packageName,
                                PackageManager.GET_META_DATA);
                    } catch (PackageManager.NameNotFoundException e) {
                        continue;
                    }
                    if (aInfo.metaData == null) {
                        Log.e(TAG, "overlay is missing metaData");
                        continue;
                    }
                    String targetPackage = aInfo.metaData.getString("target_package",
                            overlayInfo.targetPackageName);
                    boolean targetPackageInstalled;
                    ApplicationInfo targetInfo = null;
                    try {
                        targetInfo =
                                getPackageManager().getApplicationInfo(targetPackage, 0);
                        targetPackageInstalled = true;
                    }
                    catch (PackageManager.NameNotFoundException ex) {
                        targetPackageInstalled = false;
                    }
                    if (isSystemUIOverlay(targetPackage)) {
                        overlay = new Overlay(getSystemUIOverlayName(targetPackage),
                                targetPackage, targetPackageInstalled);
                    } else {
                        String overlayName = (targetInfo != null)
                                ? targetInfo.loadLabel(getPackageManager()).toString()
                                : aInfo.loadLabel(getPackageManager()).toString();
                        Log.d("TEST", "overlayName - " + overlayName);
                        overlay = new Overlay(overlayName, targetPackage, targetPackageInstalled);
                    }
                    if (overlay != null) {
                        overlay.overlayPackage = overlayInfo.packageName;
                        overlay.isOverlayEnabled =
                                (overlayInfo.state == OverlayInfo.STATE_APPROVED_ENABLED);
                        overlay.overlayVersion = info.metaData.getInt("overlay_version", -1);
                        overlay.themeVersion = info.metaData.getString("theme_version", "");
                        overlay.themePackage = info.metaData.getString("theme_package", null);
                        if (overlay.themePackage == null) {
                            // fallback substratum compatibility
                            overlay.themePackage =
                                    String.format("%s (Substratum)",
                                            aInfo.metaData.getString("Substratum_Parent", null));
                        }
                        overlay.isOverlayInstalled = true;
                        overlayGroup.overlays.add(overlay);
                    }
                }
            }
            if (!overlayGroup.overlays.isEmpty()) {
                overlayGroup.sort();
                info.groups.put(OverlayGroup.OVERLAYS, overlayGroup);
            }

            // bootanimation
            OverlayGroup bootanimationGroup = new OverlayGroup();
            final File bootanimBinary = new File(BOOTANIMATION_FILE);
            final File bootanimMetadata = new File(BOOTANIMATION_METADATA);
            if (bootanimBinary.exists() && bootanimMetadata.exists()) {
                try {
                    final Gson gson = new GsonBuilder().create();
                    final String json = FileUtils.readFileToString(bootanimMetadata,
                            Charset.defaultCharset());
                    final Overlay overlay = gson.fromJson(json, Overlay.class);
                    overlay.isOverlayEnabled = true;
                    overlay.isOverlayInstalled = true;
                    overlay.checked = false;
                    bootanimationGroup.overlays.add(overlay);
                }
                catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
            if (!bootanimationGroup.overlays.isEmpty()) {
                info.groups.put(OverlayGroup.BOOTANIMATIONS, bootanimationGroup);
            }

            // fonts
            OverlayGroup fontGroup = new OverlayGroup();
            final File fonts = new File(THEME_FONT_PATH);
            final File fontMetadata = new File(THEME_FONT_METADATA);
            if (fonts.list() != null && fonts.list().length > 0 && fontMetadata.exists()) {
                try {
                    final Gson gson = new GsonBuilder().create();
                    final String json = FileUtils.readFileToString(fontMetadata,
                            Charset.defaultCharset());
                    final Overlay overlay = gson.fromJson(json, Overlay.class);
                    overlay.isOverlayEnabled = true;
                    overlay.isOverlayInstalled = true;
                    overlay.checked = false;
                    fontGroup.overlays.add(overlay);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            if (!fontGroup.overlays.isEmpty()) {
                fontGroup.sort();
                info.groups.put(OverlayGroup.FONTS, fontGroup);
            }
        }

        @Override
        public void getThemeContent(Theme theme, OverlayThemeInfo info) throws RemoteException {
            PackageManager pm = getPackageManager();
            if (pm != null) {
                ThemePrefs prefs = getThemePrefs(theme.packageName + "_prefs");
                try {
                    Context themeContext =
                            getBaseContext().createPackageContext(theme.packageName, 0);
                    String[] olays = themeContext.getAssets().list("overlays");
                    if (olays.length > 0) {
                        info.groups.put(OverlayGroup.OVERLAYS,
                                getOverlays(themeContext, olays, prefs));
                    }
                    File themeCache = setupCache(theme.packageName);
                    String[] fonts = themeContext.getAssets().list("fonts");
                    if (fonts.length > 0) {
                        OverlayGroup fontGroup = new OverlayGroup();
                        for (String font : fonts) {
                            Overlay fon = new Overlay(font, OverlayGroup.FONTS, true);
                            createFontCache(themeContext, fon, themeCache);
                            fontGroup.overlays.add(fon);
                        }
                        info.groups.put(OverlayGroup.FONTS, fontGroup);
                    }

                    String[] sounds = themeContext.getAssets().list("audio");
                    if (sounds.length > 0) {
                        OverlayGroup soundGroup = new OverlayGroup();
                        for (String sound : sounds) {
                            String name = sound.substring(0, sound.lastIndexOf("."));
                            Overlay so = new Overlay(name, OverlayGroup.SOUNDS, true);
                            so.overlayPackage = sound;
                            cacheSounds(themeContext, themeCache, so);
                            soundGroup.overlays.add(so);
                        }
                        info.groups.put(OverlayGroup.SOUNDS, soundGroup);

                    }

                    String[] bootanis = themeContext.getAssets().list("bootanimation");
                    if (bootanis.length > 0) {
                        OverlayGroup bootanimations = new OverlayGroup();
                        for (String bootani : bootanis) {
                            String bootName = bootani.substring(0, bootani.lastIndexOf("."));
                            // cache bootanimation for further preview
                            File bootanimFile = new File(getCacheDir(),
                                    theme.packageName + "/bootanimation/" + bootani);
                            if (bootanimFile.exists()) {
                                bootanimFile.delete();
                            }

                            parseBootanimation(themeContext, bootName, bootanimFile);

                            try {
                                Shell.chmod(bootanimFile.getAbsolutePath(), 744);
                                Shell.chmod(bootanimFile.getParent(), 744);
                                Shell.chmod(bootanimFile.getParentFile().getParent(), 744);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            Overlay bootanimation = new Overlay(bootName,
                                    OverlayGroup.BOOTANIMATIONS, true);
                            bootanimation.tag = bootanimFile.getAbsolutePath();
                            bootanimations.overlays.add(bootanimation);
                        }
                        info.groups.put(OverlayGroup.BOOTANIMATIONS, bootanimations);
                    }

                    ApplicationInfo aInfo = getPackageManager().getApplicationInfo(
                            theme.packageName, PackageManager.GET_META_DATA);
                    String wallpapersXmlUri = aInfo.metaData.getString("Substratum_Wallpapers");
                    if (wallpapersXmlUri != null && isOnline()) {
                        try {
                            OverlayGroup wallpapers = new OverlayGroup();
                            URL url = new URL(wallpapersXmlUri);
                            InputStream is = url.openStream();
                            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                            XmlPullParser parser = factory.newPullParser();
                            parser.setInput(new InputStreamReader(is));
                            Overlay wallpaper = null;
                            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                                if (parser.getEventType() == XmlPullParser.START_TAG) {
                                    if (parser.getName().equals("wallpaper")) {
                                        String id = parser.getAttributeValue(null, "id");
                                        wallpaper = new Overlay(id, id, true);
                                    } else if (parser.getName().equals("link")) {
                                        assert wallpaper != null;
                                        wallpaper.tag = parser.nextText();
                                    }
                                } else if (parser.getEventType() == XmlPullParser.END_TAG) {
                                    if (parser.getName().equals("wallpaper")) {
                                        assert wallpaper != null;
                                        wallpapers.overlays.add(wallpaper);
                                    }
                                }
                                parser.next();
                            }

                            is.close();
                            info.groups.put(OverlayGroup.WALLPAPERS, wallpapers);
                        }
                        catch (Exception ex) {
                            // something went wrong, no wallpapers for you
                            ex.printStackTrace();
                        }
                    }
                } catch (PackageManager.NameNotFoundException|IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @SuppressLint("SetWorldReadable")
        @Override
        public boolean installOverlaysFromTheme(Theme theme, OverlayThemeInfo info)
                throws RemoteException {
            final int totalCount = info.getSelectedCount();
            if (totalCount == 0) {
                return false;
            }
            int index = 0;

            if (mPMUtils == null) {
                mPMUtils = new PackageManagerUtils(getBaseContext());
            }
            startWakeLock();

            try {
                notifyInstallProgress(totalCount, 0, null);
                File themeCache = setupCache(theme.packageName);
                Context themeContext = getBaseContext().createPackageContext(theme.packageName, 0);
                StringBuilder sb = new StringBuilder();

                // handle overlays first
                OverlayGroup overlays = info.groups.get(OverlayGroup.OVERLAYS);
                if (overlays != null) {
                    ThemePrefs prefs = getThemePrefs(theme.packageName + "_prefs");
                    if (!TextUtils.isEmpty(overlays.selectedStyle)) {
                        Log.d(TAG, "selectedStyle=" + overlays.selectedStyle);
                        prefs.putString("selectedStyle", overlays.selectedStyle);
                    }
                    // put system overlay to the end of the list
                    // to prevent possible quirks
                    Overlay systemOverlay = overlays.findByTargetPackage("android");
                    if (systemOverlay != null) {
                        overlays.overlays.remove(systemOverlay);
                        overlays.overlays.add(systemOverlay);
                    }

                    for (Overlay overlay : overlays.overlays) {
                        if (!overlay.checked) continue;
                        sb.setLength(0);
                        sb.append("Installing overlay");
                        sb.append(" name=" + overlay.overlayName);
                        notifyInstallProgress(totalCount, ++index, overlay.overlayName);

                        // check if installed and latest
                        sb.append(", package=" + overlay.overlayPackage);
                        sb.append(", newVersion=" + theme.themeVersion);
                        try {
                            ApplicationInfo aInfo = getPackageManager().getApplicationInfo(
                                    overlay.overlayPackage, PackageManager.GET_META_DATA);
                            PackageInfo appInfo = getPackageManager().getPackageInfo(
                                    overlay.targetPackage, 0);
                            if (aInfo.metaData != null) {
                                int overlayVersion =
                                        aInfo.metaData.getInt("overlay_version", -1);
                                sb.append(", installedVersion=" + overlayVersion);
                                int appVersion =
                                        aInfo.metaData.getInt("app_version", -1);
                                if (overlayVersion == theme.themeVersionCode
                                        && appVersion == appInfo.versionCode
                                        && !checkStyles(overlay, prefs)) {
                                    Log.d(TAG, sb.toString());
                                    Log.d(TAG, "Skipped");
                                    continue;
                                }
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            sb.append(", installedVersion=null");
                        }

                        File overlayFolder = new File(themeCache, overlay.targetPackage);
                        if (overlayFolder.exists()) {
                            deleteContents(overlayFolder);
                        }
                        AssetUtils.copyAssetFolder(themeContext.getAssets(), "overlays/"
                                        + overlay.targetPackage + "/res",
                                overlayFolder.getAbsolutePath() + "/res");
                        if (!TextUtils.isEmpty(overlays.selectedStyle)) {
                            AssetUtils.copyAssetFolder(themeContext.getAssets(), "overlays/"
                                            + overlay.targetPackage + "/" + overlays.selectedStyle,
                                    overlayFolder.getAbsolutePath() + "/res");
                        }

                        // handle type 2 overlay if non-default selected
                        OverlayFlavor type2 = overlay.flavors.get("type2");
                        if (type2 != null) {
                            sb.append(", type2=" + type2.selected);
                            AssetUtils.copyAssetFolder(themeContext.getAssets(), "overlays/"
                                            + overlay.targetPackage + "/" + type2.selected,
                                    overlayFolder.getAbsolutePath() + "/res");
                            prefs.putTypeSelection(overlay.targetPackage, "type2", type2.selected);
                        } else {
                            sb.append(", type2=null");
                        }

                        Log.d(TAG, sb.toString());
                        sb.setLength(0);
                        sb.append("Available flavors:");
                        for (String flavor : overlay.flavors.keySet()) {
                            sb.append(" " + flavor);
                        }
                        Log.d(TAG, sb.toString());
                        // handle type1 last
                        handleExtractType1Flavor(
                                themeContext, overlay, "type1a", overlayFolder, prefs);
                        handleExtractType1Flavor(
                                themeContext, overlay, "type1b", overlayFolder, prefs);
                        handleExtractType1Flavor(
                                themeContext, overlay, "type1c", overlayFolder, prefs);

                        PackageInfo appInfo =
                                getPackageManager().getPackageInfo(overlay.targetPackage, 0);
                        generateManifest(theme, overlay,
                                overlayFolder.getAbsolutePath(), appInfo.versionCode);
                        if (!compileOverlay(theme, overlay, overlayFolder.getAbsolutePath())) {
                            continue;
                        }
                        installAndEnable(getCacheDir().getAbsolutePath() + "/" + theme.packageName +
                                "/overlays/" + theme.packageName + "." + overlay.targetPackage +
                                ".apk", theme.packageName + "." + overlay.targetPackage);
                        mReboot = true;
                    }
                }

                // now for the bootanimation
                overlays = info.groups.get(OverlayGroup.BOOTANIMATIONS);
                if (overlays != null) {
                    final File bootanimBinary = new File(BOOTANIMATION_FILE);
                    final File bootanimMetadata = new File(BOOTANIMATION_METADATA);
                    final Gson gson = new GsonBuilder().create();

                    for (Overlay overlay : overlays.overlays) {
                        if (overlay.checked) {
                            notifyInstallProgress(totalCount, ++index, overlay.overlayName);

                            // cleaning up previous installation
                            if (bootanimBinary.exists()) {
                                bootanimBinary.delete();
                            }
                            if (bootanimMetadata.exists()) {
                                bootanimMetadata.delete();
                            }

                            // apply bootanimation
                            File bootAnimCache = new File(overlay.tag);
                            if (bootAnimCache.exists()) {
                                bootAnimCache.renameTo(bootanimBinary);
                            } else {
                                parseBootanimation(themeContext, overlay.overlayName,
                                        bootanimBinary);
                            }
                            // chmod 644
                            try {
                                Shell.chmod(bootanimBinary.getAbsolutePath(), 644);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                            // save metadata
                            try {
                                final String json = gson.toJson(overlay);
                                FileUtils.writeStringToFile(
                                        bootanimMetadata, json, Charset.defaultCharset());
                                // chmod 644
                                Shell.chmod(bootanimMetadata.getAbsolutePath(), 644);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            break;
                        }
                    }
                }

                overlays = info.groups.get(OverlayGroup.FONTS);
                if (overlays != null) {
                    for (Overlay overlay : overlays.overlays) {
                        if (overlay.checked) {
                            File fontMetadata = new File(THEME_FONT_METADATA);
                            File fontPath = new File(THEME_FONT_PATH);

                            if (fontMetadata.exists() && !fontMetadata.delete()) {
                                Log.e(TAG, "Unable to delete " + fontMetadata.getPath());
                            }

                            if (fontPath.list() != null &&
                                    fontPath.list().length > 0 && !fontPath.delete()) {
                                Log.e(TAG, "Unable to delete " + fontPath.getPath());
                            }

                            if (!fontPath.exists() && !fontPath.mkdirs()) {
                                Log.e(TAG, "Unable to create dir " + fontPath.getPath());
                            }

                            File fontCache = new File(overlay.tag);
                            if (!fontCache.exists()
                                    || (fontCache.list() != null && fontCache.list().length == 0)) {
                                createFontCache(themeContext, overlay, themeCache);
                            }
                            try {
                                FileUtils.copyDirectory(new File(overlay.tag),
                                        new File(THEME_FONT_PATH));
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            final Gson gson = new GsonBuilder().create();
                            // save metadata
                            try {
                                final String json = gson.toJson(overlay);
                                FileUtils.writeStringToFile(
                                        fontMetadata, json, Charset.defaultCharset());
                                // chmod 644
                                Shell.chmod(fontMetadata.getAbsolutePath(), 644);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                            Shell.chmodDir(fontPath, 747);
                            Shell.chmod(fontMetadata, 644);
                            updateFonts();
                            break;
                        }
                    }
                }

                // sounds
                overlays = info.groups.get(OverlayGroup.SOUNDS);
                if (overlays != null) {
                    for (Overlay overlay : overlays.overlays) {
                        if (!overlay.checked) continue;

                        copyAndSetSounds(themeContext, themeCache, overlay);
                    }
                }

                mOverlayManager.refresh(UserHandle.USER_CURRENT);
                sendFinishedBroadcast();
                notifyInstallComplete();
                // Housekeeping: cleanup cache
                deleteContents(themeCache);
                new File(getAapt()).delete();
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            } finally {
                stopWakeLock();
            }
            return false;
        }

        @Override
        public boolean uninstallOverlays(OverlayGroup group) throws RemoteException {
            // put system overlay to the end of the list
            // to prevent possible quirks
            Overlay systemOverlay = group.findByTargetPackage("android");
            if (systemOverlay != null) {
                group.overlays.remove(systemOverlay);
                group.overlays.add(systemOverlay);
            }

            List<Overlay> overlays = new ArrayList<>();
            for (Overlay overlay : group.overlays) {
                if (overlay.checked) {
                    overlays.add(overlay);
                }
            }
            if (overlays == null || overlays.isEmpty()) return false;

            if (mPMUtils == null) {
                mPMUtils = new PackageManagerUtils(getBaseContext());
            }
            startWakeLock();
            try {
                notifyUninstallProgress(overlays.size(), 0, null);

                Map<String, List<OverlayInfo>> overlayInfos = new HashMap<>();
                try {
                    overlayInfos = mOverlayManager.getAllOverlays(UserHandle.USER_CURRENT);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }

                final StringBuilder sb = new StringBuilder();
                for (Overlay overlay : overlays) {
                    sb.setLength(0);
                    sb.append("Uninstalling overlay");
                    sb.append(" name=" + overlay.overlayName);

                    // bootanimation
                    if (overlay.targetPackage.equals(OverlayGroup.BOOTANIMATIONS)) {
                        notifyUninstallProgress(overlays.size(), overlays.indexOf(overlay),
                                overlay.overlayName);
                        sb.append(", type=bootanimation");
                        final File bootanimBinary = new File(BOOTANIMATION_FILE);
                        final File bootanimMetadata = new File(BOOTANIMATION_METADATA);
                        if (bootanimBinary.exists()) {
                            bootanimBinary.delete();
                        }
                        if (bootanimMetadata.exists()) {
                            bootanimMetadata.delete();
                        }
                        Log.d(TAG, sb.toString());
                        Log.d(TAG, "Complete");
                        continue;
                    }

                    // fonts
                    if (overlay.targetPackage.equals(OverlayGroup.FONTS)) {
                        notifyUninstallProgress(overlays.size(), overlays.indexOf(overlay),
                                overlay.overlayName);
                        sb.append(", type=font");
                        final File fonts = new File("/data/system/theme/fonts/");
                        final File fontMetadata = new File("/data/system/theme/font.json");
                        if (fonts.exists()) {
                            deleteContents(fonts);
                            fonts.delete();
                        }
                        if (fontMetadata.exists()) {
                            fontMetadata.delete();
                        }
                        updateFonts();
                    }

                    sb.append(", package=" + overlay.overlayPackage);
                    Log.d(TAG, sb.toString());
                    List<OverlayInfo> ois = overlayInfos.get(getTargetPackage(overlay.targetPackage));
                    if (ois != null) {
                        mReboot = true;
                        for (OverlayInfo oi : ois) {
                            if (oi.packageName.equals(overlay.overlayPackage)) {
                                notifyUninstallProgress(overlays.size(), overlays.indexOf(overlay),
                                        overlay.overlayName);
                                mOverlayManager.setEnabled(overlay.overlayPackage,
                                        false, UserHandle.USER_CURRENT, false);
                                if (mPMUtils.uninstallPackage(overlay.overlayPackage)) {
                                    Log.d(TAG, "Complete");
                                } else {
                                    Log.e(TAG, "Failed");
                                }
                                break;
                            }
                            Log.e(TAG, "No package name match found for " + overlay.overlayPackage);
                        }
                    } else {
                        Log.d(TAG, "No installed overlays found for target package "
                                + getTargetPackage(overlay.targetPackage));
                    }
                }
                sendFinishedBroadcast();
                notifyUninstallComplete();
                return true;
            } finally {
                stopWakeLock();
            }
        }

        @Override
        public boolean isRebootRequired() throws RemoteException {
            return mReboot;
        }

        @Override
        public void reboot() throws RemoteException {
            ActivityManagerNative.getDefault().restart();
        }
    }

    private void generateManifest(Theme theme, Overlay overlay, String path, int appVersion) {
        String targetPackage = getTargetPackage(overlay.targetPackage);
        StringBuilder manifest = new StringBuilder();
        manifest.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        manifest.append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n");
        manifest.append("package=\"" + theme.packageName + "." + overlay.targetPackage + "\">\n");
        manifest.append("<overlay\n");
        manifest.append("android:targetPackage=\"" + targetPackage + "\"/>\n");
        manifest.append("<application>\n");
        manifest.append("<meta-data android:name=\"theme_version\" android:value=\""
                + theme.themeVersion + "\"/>\n");
        manifest.append("<meta-data android:name=\"overlay_version\" android:value=\""
                + theme.themeVersionCode + "\"/>\n");
        manifest.append("<meta-data android:name=\"theme_package\" android:value=\""
                + theme.packageName + "\"/>\n");
        manifest.append("<meta-data android:name=\"target_package\" android:value=\""
                + overlay.targetPackage + "\"/>\n");
        manifest.append("<meta-data android:name=\"app_version\" android:value=\""
                + appVersion + "\"/>\n");
        manifest.append("</application>\n");
        manifest.append("</manifest>");
        try {
            FileUtils.writeStringToFile(new File(path, "AndroidManifest.xml"), manifest.toString(),
                    Charset.defaultCharset());
        } catch (IOException e) {
        }
    }

    private boolean checkStyles(Overlay overlay, ThemePrefs prefs) {
        boolean changed = false;
        for (OverlayFlavor flavor : overlay.flavors.values()) {
            String ins = prefs.getTypeSelection(overlay.targetPackage, flavor.key);
            Log.d(TAG, "selected - " + flavor.selected);
            Log.d(TAG, "saved - " + ins);
            changed |= ins != null && flavor.selected != null && !ins.equals(flavor.selected);
        }
        Log.d(TAG, overlay.targetPackage + " : changed - " + changed);
        return changed;
    }

    private boolean compileOverlay(Theme theme, Overlay overlay, String overlayPath) {
        File overlayFolder = new File(getCacheDir() + "/" + theme.packageName + "/overlays");
        if (!overlayFolder.exists()) {
            overlayFolder.mkdirs();
        }
        File unsignedOverlay = new File(overlayFolder,
                theme.packageName + "." + overlay.targetPackage + "_unsigned.apk");
        File signedOverlay = new File(overlayFolder,
                theme.packageName + "." + overlay.targetPackage + ".apk");
        if (unsignedOverlay.exists()) {
            unsignedOverlay.delete();
        }
        if (signedOverlay.exists()) {
            signedOverlay.delete();
        }

        Log.d(TAG,"compileOverlay: overlayPath     = " + overlayPath);
        Log.d(TAG,"compileOverlay: overlayFolder   = " + overlayFolder.getAbsolutePath());
        Log.d(TAG,"compileOverlay: unsignedOverlay = " + unsignedOverlay.getAbsolutePath());
        Log.d(TAG,"compileOverlay: signedOverlay   = " + signedOverlay.getAbsolutePath());

        ApplicationInfo info = null;
        try {
            info = getPackageManager().getApplicationInfo(getTargetPackage(overlay.targetPackage),
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG,"Target package " + getTargetPackage(overlay.targetPackage) + " not found");
            return false;
        }

        Log.d(TAG,"compileOverlay: info.sourceDir  = " + info.sourceDir);

        try {
            Process nativeApp = Runtime.getRuntime().exec(new String[]{
                    getAapt(), "p",
                    "-M", overlayPath + "/AndroidManifest.xml",
                    "-S", overlayPath + "/res",
                    "-I", "/system/framework/framework-res.apk",
                    info != null ? "-I" : "", info != null ? info.sourceDir : "",
                    "-F", unsignedOverlay.getAbsolutePath()
            });
            nativeApp.waitFor();
            int exitCode = nativeApp.exitValue();
            String error = IOUtils.toString(nativeApp.getErrorStream(), Charset.defaultCharset());
            if (exitCode != 0 || !TextUtils.isEmpty(error)) {
                Log.e(TAG, "aapt: exitCode:" + exitCode + " error: " + error);
                return false;
            }
            // sign
            if (unsignedOverlay.exists()) {
                ZipSigner zipSigner = new ZipSigner();
                zipSigner.setKeymode("testkey");
                zipSigner.signZip(unsignedOverlay.getAbsolutePath(),
                    signedOverlay.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create overlay - unable to compile "
                        + unsignedOverlay.getAbsolutePath());
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void updateFonts() {
        SystemProperties.set("sys.refresh_theme", "1");
        Typeface.recreateDefaults();
        float fontSize = Settings.System.getFloatForUser(getContentResolver(),
                Settings.System.FONT_SCALE, 1.0f, UserHandle.USER_CURRENT);
        Settings.System.putFloatForUser(getContentResolver(),
                Settings.System.FONT_SCALE, fontSize + 0.0001f, UserHandle.USER_CURRENT);
    }

    private void createFontCache(Context themeContext, Overlay overlay, File themeCache) {
        File fontPath = new File(themeCache, "fonts/" + overlay.overlayName.replace(".zip", ""));
        if (!fontPath.exists() && !fontPath.mkdirs()) {
            Log.e(TAG, "Unable to create directory " + fontPath.getPath());
        }
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new BufferedInputStream(themeContext.getAssets().open("fonts/" + overlay.overlayName)))) {
            ZipEntry entry;
            int count;
            byte[] buf = new byte[4096];
            while ((entry = zipInputStream.getNextEntry()) != null) {
                File file = new File(fontPath, entry.getName());
                if (entry.isDirectory()) continue;
                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = zipInputStream.read(buf)) != -1) {
                        outputStream.write(buf, 0, count);
                    }
                }
            }
            writeFontsXML(fontPath.getAbsolutePath());
            Shell.chmodDir(fontPath, 777);
            overlay.tag = fontPath.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("SetWorldReadable")
    private String getAapt() {
        String path = "/data/system/theme/bin";
        File aaptDir = new File(path);
        File aaptFile = new File(aaptDir, "aapt");
        if (aaptFile.exists()) {
        } else {
            try {
                copyInputStreamToFile(getAssets().open("aapt"), aaptFile);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
        // chmod 700
        try {
            Shell.chmod(aaptDir.getAbsolutePath(), 700);
            Shell.chmod(aaptFile.getAbsolutePath(), 700);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return aaptFile.getAbsolutePath();
    }

    private void installAndEnable(String apk, String packageName) {
        try {
            if (mPMUtils.installPackage(apk)) {
                Log.d(TAG, "Successfully installed overlay - " + packageName);
                OverlayInfo info = null;
                while (info == null) {
                    try {
                        info = mOverlayManager.getOverlayInfo(packageName, UserHandle.USER_CURRENT);
                        Thread.sleep(500);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                try {
                    if (!mOverlayManager.setEnabled(packageName,
                            true, UserHandle.USER_CURRENT, false)) {
                        Log.e(TAG, "Failed to enable overlay - " + packageName);
                    } else {
                        Log.d(TAG, "Successfully enabled overlay - " + packageName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception while enabling overlay - " + packageName);
                    e.printStackTrace();
                }
            } else {
                Log.e(TAG, "Failed to install package " + apk);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while installing overlay - " + packageName);
            e.printStackTrace();
        }
    }

    private OverlayGroup getOverlays(Context themeContext,
            String[] packages, ThemePrefs prefs) {
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
                overlay = new Overlay(getSystemUIOverlayName(p), p, true);
            } else {
                try {
                    ApplicationInfo info = getPackageManager().getApplicationInfo(p, 0);
                    // don't show frozen apps
                    if (info.enabled) {
                        overlay = new Overlay((String) info.loadLabel(getPackageManager()), p, true);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    //overlay = new Overlay(p, p, false);
                }
            }
            if (overlay != null) {
                overlay.overlayPackage = themeContext.getPackageName() + "."
                        + overlay.targetPackage;
                List<OverlayInfo> ois = overlays.get(getTargetPackage(overlay.targetPackage));
                if (ois != null) {
                    for (OverlayInfo oi : ois) {
                        if (oi.packageName.equals(overlay.overlayPackage)) {
                            overlay.isOverlayInstalled = true;
                            overlay.checked = (oi.state == OverlayInfo.STATE_APPROVED_ENABLED);
                            overlay.isOverlayEnabled =
                                    (oi.state == OverlayInfo.STATE_APPROVED_ENABLED);

                            try {
                                ApplicationInfo oai = getPackageManager()
                                        .getApplicationInfo(overlay.overlayPackage,
                                                PackageManager.GET_META_DATA);
                                overlay.overlayVersion = oai.metaData.getInt("overlay_version");
                                overlay.themeVersion = oai.metaData.getString("theme_version", "");
                            } catch (PackageManager.NameNotFoundException ex) {
                                // ignore
                            }
                            break;
                        }
                    }
                }
                loadOverlayFlavors(themeContext, overlay);
                for (OverlayFlavor flavor : overlay.flavors.values()) {
                    String sel = prefs.getTypeSelection(overlay.targetPackage, flavor.key);
                    if (!TextUtils.isEmpty(sel)) {
                        flavor.selected = sel;
                    }
                }
                group.overlays.add(overlay);
            }
        }
        getThemeStyles(themeContext, group);
        group.sort();
        return group;
    }

    private String getTargetPackage(String targetPackage) {
        if (mSystemUIPackages.containsKey(targetPackage)) {
            return "com.android.systemui";
        }
        return targetPackage;
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
                if (flavor.endsWith(".enc")) {
                    flavor = flavor.substring(0, flavor.lastIndexOf("."));
                }
                if (flavor.contains("res")
                        || flavor.contains("type3")) {
                    continue;
                }
                if (flavor.startsWith("type")) {
                    if (!flavor.contains("_")) {
                        if (!isValidFlavor(flavor)) {
                            continue;
                        }
                        try {
                            String flavorName = IOUtils.toString(themeContext.getAssets().open(
                                    "overlays/" + overlay.targetPackage + "/" + flavor),
                                            Charset.defaultCharset());
                            flavorMap.put(flavor, new OverlayFlavor(flavor, flavorName));
                        } catch (IOException e) {
                            // ignore
                        }
                    } else {
                        String flavorName = flavor.substring(flavor.indexOf("_") + 1);
                        if (flavorName.contains(".")) {
                            flavorName = flavorName.substring(0, flavorName.indexOf("."));
                        }
                        String key = flavor.substring(0, flavor.indexOf("_"));
                        if (!isValidFlavor(key)) {
                            continue;
                        }
                        if (!flavorMap.containsKey(key)) {
                            flavorMap.put(key, new OverlayFlavor(flavor, flavorName));
                        } else {
                            flavorMap.get(key).flavors.put(flavor, flavorName);
                        }
                    }
                }
            }
            overlay.flavors.putAll(flavorMap);
        }
    }
    
    private boolean isValidFlavor(String flavor) {
        if (flavor.equals("type1a") || flavor.equals("type1b") || flavor.equals("type1c")
                || flavor.equals("type2")) {
            return true;
        }
        return false;
    }


    private void getThemeStyles(Context themeContext, OverlayGroup group) {
        String[] types = null;
        try {
            types = themeContext.getAssets().list("overlays/android");
            String def = IOUtils.toString(themeContext.getAssets().open("overlays/android/"
                    + "type3"), Charset.defaultCharset());
            boolean hasDefault = false;
            for (String type : types) {
                if (type.equals("res")) {
                    hasDefault = true;
                    break;
                }
            }
            group.styles.put((hasDefault ? "type3" : ""), def);
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

    private String getSystemUIOverlayName(String pName) {
        return mSystemUIPackages.get(pName);
    }

    boolean isSystemUIOverlay(String pName) {
       return mSystemUIPackages.containsKey(pName);
    }

    private File setupCache(String packageName) {
        File cache = new File(getCacheDir(), packageName);
        if (cache.exists()) {
            // Always start on a clean slate
            deleteContents(cache);
        }
        if (!cache.mkdirs()) {
            Log.e(TAG, "unable to create directory : "
                    + cache.getAbsolutePath());
        }
        return cache;
    }

    private void handleExtractType1Flavor(Context themeContext, Overlay overlay, String typeName,
                                          File overlayFolder, ThemePrefs prefs) {
        OverlayFlavor type = overlay.flavors.get(typeName);
        if (type != null) {
            Log.d(TAG, "handleExtractType1Flavor, selected=" + type.selected);
            if (type.selected == null) return;
            AssetManager am = themeContext.getAssets();
            try {
                String of = "overlays/" + overlay.targetPackage + "/res";
                for (String n : am.list(of)) {
                    if (n.contains("values")) {
                        for (String s : am.list(of + "/" + n)) {
                            if (s.equals(type.key + ".xml")) {
                                AssetUtils.copyAsset(am, "overlays/" + overlay.targetPackage
                                                + "/" + type.selected,
                                        overlayFolder.getAbsolutePath() + "/res/"
                                                + n + "/" + type.key + ".xml");
                            }
                        }
                    }
                }
                prefs.putTypeSelection(overlay.targetPackage, typeName, type.selected);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            Log.d(TAG, "Flavor " + typeName + " is null!");
        }
    }

    private boolean isOnline() {
        final NetworkInfo ni = mConnectivityManager.getActiveNetworkInfo();
        return (ni != null && ni.isConnected());
    }

    private void sendFinishedBroadcast() {
        Intent intent = new Intent("slim.action.INSTALL_FINISHED");
        sendBroadcast(intent);
    }

    private boolean parseBootanimation(Context themeContext, String bootAnimName, File bootanimFile) {
        File bootanimCacheFile = new File(bootanimFile.getParent(), "__" + bootanimFile.getName());
        if (bootanimCacheFile.exists()) {
            bootanimCacheFile.delete();
        }

        // extract the asset first
        AssetUtils.copyAsset(themeContext.getAssets(), "bootanimation/"
                + bootAnimName + ".zip", bootanimCacheFile.getAbsolutePath());

        try {
            // check if it's a flashable zip
            ZipFile zip = new ZipFile(bootanimCacheFile);
            ZipEntry ze;
            boolean isFlashableZip = false;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ze = e.nextElement();
                final String zeName = ze.getName();
                if (zeName.startsWith("META-INF")) {
                    isFlashableZip = true;
                    break;
                } else if (zeName.startsWith("part0")) {
                    isFlashableZip = false;
                    break;
                }
            }
            if (isFlashableZip) {
                // extract the proper one
                ze = zip.getEntry("system/media/bootanimation.zip");
                if (ze != null) {
                    InputStream stream = zip.getInputStream(ze);
                    try {
                        copyAndScaleBootAnimation(stream, bootanimFile.getAbsolutePath());
                    } finally {
                        stream.close();
                    }
                }
            } else {
                // easy part, just rename the file
                copyAndScaleBootAnimation(new FileInputStream(bootanimCacheFile),
                        bootanimFile.getAbsolutePath());
            }
            return true;
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        } finally {
            if (bootanimCacheFile.exists()) {
                bootanimCacheFile.delete();
            }
        }
    }

    /**
     * Scale the boot animation to better fit the device by editing the desc.txt found
     * in the bootanimation.zip
     * @param context Context to use for getting an instance of the WindowManager
     * @param input InputStream of the original bootanimation.zip
     * @param dst Path to store the newly created bootanimation.zip
     * @throws IOException
     */
    private void copyAndScaleBootAnimation(InputStream input, String dst) throws IOException {
        final OutputStream os = new FileOutputStream(dst);
        final ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os));
        final ZipInputStream bootAni = new ZipInputStream(new BufferedInputStream(input));
        ZipEntry ze;

        zos.setMethod(ZipOutputStream.STORED);
        final byte[] bytes = new byte[4096];
        int len;
        while ((ze = bootAni.getNextEntry()) != null) {
            ZipEntry entry = new ZipEntry(ze.getName());
            entry.setMethod(ZipEntry.STORED);
            entry.setCrc(ze.getCrc());
            entry.setSize(ze.getSize());
            entry.setCompressedSize(ze.getSize());
            if (!ze.getName().equals("desc.txt")) {
                // just copy this entry straight over into the output zip
                zos.putNextEntry(entry);
                while ((len = bootAni.read(bytes)) > 0) {
                    zos.write(bytes, 0, len);
                }
            } else {
                String line;
                BufferedReader reader = new BufferedReader(new InputStreamReader(bootAni));
                final String[] info = reader.readLine().split(" ");

                int scaledWidth;
                int scaledHeight;
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                // just in case the device is in landscape orientation we will
                // swap the values since most (if not all) animations are portrait
                if (dm.widthPixels > dm.heightPixels) {
                    scaledWidth = dm.heightPixels;
                    scaledHeight = dm.widthPixels;
                } else {
                    scaledWidth = dm.widthPixels;
                    scaledHeight = dm.heightPixels;
                }

                int width = Integer.parseInt(info[0]);
                int height = Integer.parseInt(info[1]);

                if (width == height)
                    scaledHeight = scaledWidth;
                else {
                    // adjust scaledHeight to retain original aspect ratio
                    float scale = (float)scaledWidth / (float)width;
                    int newHeight = (int)((float)height * scale);
                    if (newHeight < scaledHeight)
                        scaledHeight = newHeight;
                }

                CRC32 crc32 = new CRC32();
                int size = 0;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                line = String.format("%d %d %s\n", scaledWidth, scaledHeight, info[2]);
                buffer.put(line.getBytes());
                size += line.getBytes().length;
                crc32.update(line.getBytes());
                while ((line = reader.readLine()) != null) {
                    line = String.format("%s\n", line);
                    buffer.put(line.getBytes());
                    size += line.getBytes().length;
                    crc32.update(line.getBytes());
                }
                entry.setCrc(crc32.getValue());
                entry.setSize(size);
                entry.setCompressedSize(size);
                zos.putNextEntry(entry);
                zos.write(buffer.array(), 0, size);
            }
            zos.closeEntry();
        }
        zos.close();
    }

    private boolean setAudible(File sound, int type, String name, boolean ui) {
        final String mimeType = name.endsWith(".ogg") ? "application/ogg" : "application/mp3";
        ContentValues values = new ContentValues();

        values.put(MediaStore.MediaColumns.DATA, sound.getAbsolutePath());
        values.put(MediaStore.MediaColumns.TITLE, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.SIZE, sound.length());
        values.put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE && !ui);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION,
                type == RingtoneManager.TYPE_NOTIFICATION && !ui);
        values.put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM && !ui);
        values.put(MediaStore.Audio.Media.IS_MUSIC, ui);

        Uri uri = MediaStore.Audio.Media.getContentUriForPath(sound.getAbsolutePath());
        Uri newUri = null;
        Cursor c = getContentResolver().query(uri, new String[] { MediaStore.MediaColumns._ID },
                MediaStore.MediaColumns.DATA + "='" + sound.getAbsolutePath() + "'", null, null);
        if (c != null && c.getCount() > 0) {
            c.moveToFirst();
            String id = String.valueOf(c.getLong(0));
            c.close();

            newUri = Uri.withAppendedPath(Uri.parse("content://media/internal/audio/media"), id);
            getContentResolver().update(uri, values, MediaStore.MediaColumns._ID + "=" + id, null);
        }

        if (newUri == null) {
            newUri = getContentResolver().insert(uri, values);
        }

        try {
            RingtoneManager.setActualDefaultRingtoneUri(this, type, newUri);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private void copyAndSetSounds(Context themeContext, File themeCache, Overlay overlay) {
        File soundPath = new File(overlay.tag);
        if (!soundPath.exists()) {
            cacheSounds(themeContext, themeCache, overlay);
        }

        try {
            FileUtils.copyDirectory(new File(overlay.tag), new File("/data/system/theme/audio"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        ArrayList<String> paths = new ArrayList<>();
        getSounds(new File("/data/system/theme/audio"), paths);

        for (String path : paths) {
            if (path.contains("ui")) {
                if (path.contains("Lock")) {
                    Settings.Global.putStringForUser(getContentResolver(),
                            "lock_sound", path, UserHandle.USER_SYSTEM);
                } else if (path.contains("Unlock")) {
                    Settings.Global.putStringForUser(getContentResolver(),
                            "unlock_sound", path, UserHandle.USER_SYSTEM);
                } else if (path.contains("Effect_Tick")) {
                    setAudible(new File(path), RingtoneManager.TYPE_RINGTONE, new File(path).getName(), true);
                } else if (path.contains("LowBattery")) {
                    Settings.Global.putStringForUser(getContentResolver(),
                            "low_battery_sound", path, UserHandle.USER_SYSTEM);
                }
            } else if (path.contains("alarms")) {
                setAudible(new File(path), RingtoneManager.TYPE_ALARM, new File(path).getName(), false);
            } else if (path.contains("notifications")) {
                setAudible(new File(path), RingtoneManager.TYPE_NOTIFICATION, new File(path).getName(), false);
            } else if (path.contains("ringtones")) {
                setAudible(new File(path), RingtoneManager.TYPE_RINGTONE, new File(path).getName(), false);
            }
        }
    }

    private void getSounds(File path, ArrayList<String> paths) {
        for (File file : path.listFiles()) {
            if (file.isDirectory()) {
                getSounds(file, paths);
            } else {
                paths.add(file.getAbsolutePath());
            }
        }
    }

    private void cacheSounds(Context themeContext, File themeCache, Overlay sound) {
        File soundPath = new File(themeCache,  "sounds/" + sound.overlayName);
        if (!soundPath.exists() && !soundPath.mkdirs()) {
            Log.e(TAG, "Unable to create directory " + soundPath.getPath());
        }
        try (ZipInputStream zis = new ZipInputStream(
                themeContext.getAssets().open("audio/" + sound.overlayPackage))) {
            ZipEntry entry;
            int count;
            byte[] buf = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(soundPath, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                Log.d("TEST", "entry - " + file.getName());
                if (!Arrays.asList(ALLOWED_SOUNDS).contains(file.getName()))
                    continue;
                try (FileOutputStream os = new FileOutputStream(file)) {
                    while ((count = zis.read(buf)) != -1) {
                        os.write(buf, 0, count);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Shell.chmod(file, 777);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        sound.tag = soundPath.getAbsolutePath();
    }

    private void writeFontsXML(String path) {
        try {
            File fontPath = new File(path);

            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            FontListParser.Config fontConfig = FontListParser.parse(new File("/system/etc/fonts.xml"),
                    "/system/fonts");

            Document doc = docBuilder.newDocument();
            Element root = doc.createElement("familyset");
            root.setAttribute("version", "22");

            for (FontListParser.Family family : fontConfig.families) {
                Element familyEl = doc.createElement("family");
                familyEl.setAttribute("name", family.name);
                for (FontListParser.Font font : family.fonts) {
                    if (Arrays.asList(fontPath.list()).contains(new File(font.fontName).getName())) {
                        Element fon = doc.createElement("font");
                        fon.setAttribute("weight", Integer.toString(font.weight));
                        fon.setAttribute("style", font.isItalic ? "italic" : "normal");
                        fon.setTextContent(new File(font.fontName).getName());
                        familyEl.appendChild(fon);
                    }
                }
                if (familyEl.hasChildNodes()) {
                    root.appendChild(familyEl);
                }
            }
            doc.appendChild(root);

            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(fontPath, "fonts.xml"));
            transformer.transform(source, result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
