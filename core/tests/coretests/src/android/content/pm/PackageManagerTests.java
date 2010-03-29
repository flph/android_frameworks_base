/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.pm;

import com.android.frameworks.coretests.R;
import com.android.internal.content.PackageHelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.storage.IMountService;
import android.os.storage.StorageListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageResultCode;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.test.AndroidTestCase;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

public class PackageManagerTests extends AndroidTestCase {
    private static final boolean localLOGV = true;
    public static final String TAG="PackageManagerTests";
    public final long MAX_WAIT_TIME = 25*1000;
    public final long WAIT_TIME_INCR = 5*1000;
    private static final String SECURE_CONTAINERS_PREFIX = "/mnt/asec";
    private static final int APP_INSTALL_AUTO = PackageHelper.APP_INSTALL_AUTO;
    private static final int APP_INSTALL_DEVICE = PackageHelper.APP_INSTALL_INTERNAL;
    private static final int APP_INSTALL_SDCARD = PackageHelper.APP_INSTALL_EXTERNAL;
    private boolean mOrigState;

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg="+errMsg);
        fail(errMsg);
    }
    void failStr(Exception e) {
        failStr(e.getMessage());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mOrigState = getMediaState();
        if (!mountMedia()) {
            Log.i(TAG, "sdcard not mounted? Some of these tests might fail");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // Restore media state.
        boolean newState = getMediaState();
        if (newState != mOrigState) {
            if (mOrigState) {
                getMs().mountVolume(Environment.getExternalStorageDirectory().getPath());
            } else {
                getMs().unmountVolume(Environment.getExternalStorageDirectory().getPath(), true);
            }
        }
        super.tearDown();
    }

    private class PackageInstallObserver extends IPackageInstallObserver.Stub {
        public int returnCode;
        private boolean doneFlag = false;

        public void packageInstalled(String packageName, int returnCode) {
            synchronized(this) {
                this.returnCode = returnCode;
                doneFlag = true;
                notifyAll();
            }
        }

        public boolean isDone() {
            return doneFlag;
        }
    }

    abstract class GenericReceiver extends BroadcastReceiver {
        private boolean doneFlag = false;
        boolean received = false;
        Intent intent;
        IntentFilter filter;
        abstract boolean notifyNow(Intent intent);
        @Override
        public void onReceive(Context context, Intent intent) {
            if (notifyNow(intent)) {
                synchronized (this) {
                    received = true;
                    doneFlag = true;
                    this.intent = intent;
                    notifyAll();
                }
            }
        }

        public boolean isDone() {
            return doneFlag;
        }

        public void setFilter(IntentFilter filter) {
            this.filter = filter;
        }
    }

    class InstallReceiver extends GenericReceiver {
        String pkgName;

        InstallReceiver(String pkgName) {
            this.pkgName = pkgName;
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                return false;
            }
            Uri data = intent.getData();
            String installedPkg = data.getEncodedSchemeSpecificPart();
            if (pkgName.equals(installedPkg)) {
                return true;
            }
            return false;
        }
    }

    PackageManager getPm() {
        return mContext.getPackageManager();
    }

    public boolean invokeInstallPackage(Uri packageURI, int flags,
            final String pkgName, GenericReceiver receiver) throws Exception {
        PackageInstallObserver observer = new PackageInstallObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        final boolean DEBUG = true;
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().installPackage(packageURI, observer, flags, null);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for packageInstalled callback");
                    }
                    if (observer.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                        Log.i(TAG, "Failed to install with error code = " + observer.returnCode);
                        return false;
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for PACKAGE_ADDED notification");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    public boolean invokeInstallPackageFail(Uri packageURI, int flags,
            final String pkgName, int result) throws Exception {
        PackageInstallObserver observer = new PackageInstallObserver();
        try {
            // Wait on observer
            synchronized(observer) {
                getPm().installPackage(packageURI, observer, flags, null);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    throw new Exception("Timed out waiting for packageInstalled callback");
                }
                return (observer.returnCode == result);
            }
        } finally {
        }
    }

    Uri getInstallablePackage(int fileResId, File outFile) {
        Resources res = mContext.getResources();
        InputStream is = null;
        try {
            is = res.openRawResource(fileResId);
        } catch (NotFoundException e) {
            failStr("Failed to load resource with id: " + fileResId);
        }
        FileUtils.setPermissions(outFile.getPath(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IRWXO,
                -1, -1);
        assertTrue(FileUtils.copyToFile(is, outFile));
        FileUtils.setPermissions(outFile.getPath(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IRWXO,
                -1, -1);
        return Uri.fromFile(outFile);
    }

    private PackageParser.Package parsePackage(Uri packageURI) {
        final String archiveFilePath = packageURI.getPath();
        PackageParser packageParser = new PackageParser(archiveFilePath);
        File sourceFile = new File(archiveFilePath);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        PackageParser.Package pkg = packageParser.parsePackage(sourceFile, archiveFilePath, metrics, 0);
        packageParser = null;
        return pkg;
    }
    private boolean checkSd(long pkgLen) {
        String status = Environment.getExternalStorageState();
        if (!status.equals(Environment.MEDIA_MOUNTED)) {
            return false;
        }
        long sdSize = -1;
        StatFs sdStats = new StatFs(
                Environment.getExternalStorageDirectory().getPath());
        sdSize = (long)sdStats.getAvailableBlocks() *
                (long)sdStats.getBlockSize();
        // TODO check for thesholds here
        return pkgLen <= sdSize;
        
    }
    private boolean checkInt(long pkgLen) {
        StatFs intStats = new StatFs(Environment.getDataDirectory().getPath());
        long intSize = (long)intStats.getBlockCount() *
                (long)intStats.getBlockSize();
        long iSize = (long)intStats.getAvailableBlocks() *
                (long)intStats.getBlockSize();
        // TODO check for thresholds here?
        return pkgLen <= iSize;
    }
    private static final int INSTALL_LOC_INT = 1;
    private static final int INSTALL_LOC_SD = 2;
    private static final int INSTALL_LOC_ERR = -1;
    private int checkDefaultPolicy(long pkgLen) {
        // Check for free memory internally
        if (checkInt(pkgLen)) {
            return INSTALL_LOC_INT;
        }
        // Check for free memory externally
        if (checkSd(pkgLen)) {
            return INSTALL_LOC_SD;
        }
        return INSTALL_LOC_ERR;
    }
    private int getInstallLoc(int flags, int expInstallLocation, long pkgLen) {
        // Flags explicitly over ride everything else.
        if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0 ) {
            return INSTALL_LOC_INT;
        } else if ((flags & PackageManager.INSTALL_EXTERNAL) != 0 ) {
            return INSTALL_LOC_SD;
        } else if ((flags & PackageManager.INSTALL_INTERNAL) != 0) {
            return INSTALL_LOC_INT;
        }
        // Manifest option takes precedence next
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
            // TODO fitsonSd check
            if (checkSd(pkgLen)) {
               return INSTALL_LOC_SD;
            }
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            return INSTALL_LOC_ERR;
        }
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
            if (checkInt(pkgLen)) {
                return INSTALL_LOC_INT;
            }
            return INSTALL_LOC_ERR;
        }
        if (expInstallLocation == PackageInfo.INSTALL_LOCATION_AUTO) {
            return checkDefaultPolicy(pkgLen);
        }
        // Check for settings preference.
        boolean checkSd = false;
        int setLoc = 0;
        try {
            setLoc = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SET_INSTALL_LOCATION);
        } catch (SettingNotFoundException e) {
            failStr(e);
        }
        if (setLoc == 1) {
            int userPref = APP_INSTALL_AUTO;
            try {
                userPref = Settings.System.getInt(mContext.getContentResolver(), Settings.System.DEFAULT_INSTALL_LOCATION);
            } catch (SettingNotFoundException e) {
                failStr(e);
            }
            if (userPref == APP_INSTALL_DEVICE) {
                if (checkInt(pkgLen)) {
                    return INSTALL_LOC_INT;
                }
                return INSTALL_LOC_ERR;
            } else if (userPref == APP_INSTALL_SDCARD) {
                if (checkSd(pkgLen)) {
                    return INSTALL_LOC_SD;
                }
                return INSTALL_LOC_ERR;
            }
        }
        return checkDefaultPolicy(pkgLen);
    }
    
    private void assertInstall(PackageParser.Package pkg, int flags, int expInstallLocation) {
        try {
            String pkgName = pkg.packageName;
            ApplicationInfo info = getPm().getApplicationInfo(pkgName, 0);
            assertNotNull(info);
            assertEquals(pkgName, info.packageName);
            File dataDir = Environment.getDataDirectory();
            String appInstallPath = new File(dataDir, "app").getPath();
            String drmInstallPath = new File(dataDir, "app-private").getPath();
            File srcDir = new File(info.sourceDir);
            String srcPath = srcDir.getParent();
            File publicSrcDir = new File(info.publicSourceDir);
            String publicSrcPath = publicSrcDir.getParent();
            long pkgLen = new File(info.sourceDir).length();

            if ((flags & PackageManager.INSTALL_FORWARD_LOCK) != 0) {
                assertTrue((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
                assertEquals(srcPath, drmInstallPath);
                assertEquals(publicSrcPath, appInstallPath);
            } else {
                assertFalse((info.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0);
                int rLoc = getInstallLoc(flags, expInstallLocation, pkgLen);
                if (rLoc == INSTALL_LOC_INT) {
                    assertEquals(srcPath, appInstallPath);
                    assertEquals(publicSrcPath, appInstallPath);
                    assertFalse((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                } else if (rLoc == INSTALL_LOC_SD){
                    assertTrue((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                    assertTrue(srcPath.startsWith(SECURE_CONTAINERS_PREFIX));
                    assertTrue(publicSrcPath.startsWith(SECURE_CONTAINERS_PREFIX));
                } else {
                    // TODO handle error. Install should have failed.
                }
            }
        } catch (NameNotFoundException e) {
            failStr("failed with exception : " + e);
        }
    }
    
    private void assertNotInstalled(String pkgName) {
        try {
            ApplicationInfo info = getPm().getApplicationInfo(pkgName, 0);
            fail(pkgName + " shouldnt be installed");
        } catch (NameNotFoundException e) {
        }
    }

    class InstallParams {
        String outFileName;
        Uri packageURI;
        PackageParser.Package pkg;
        InstallParams(PackageParser.Package pkg, String outFileName, Uri packageURI) {
            this.outFileName = outFileName;
            this.packageURI = packageURI;
            this.pkg = pkg;
        }
    }

    private InstallParams sampleInstallFromRawResource(int flags, boolean cleanUp) {
        return installFromRawResource("install.apk", R.raw.install, flags, cleanUp,
                false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    static final String PERM_PACKAGE = "package";
    static final String PERM_DEFINED = "defined";
    static final String PERM_UNDEFINED = "undefined";
    static final String PERM_USED = "used";
    static final String PERM_NOTUSED = "notused";
    
    private void assertPermissions(String[] cmds) {
        final PackageManager pm = getPm();
        String pkg = null;
        PackageInfo pkgInfo = null;
        String mode = PERM_DEFINED;
        int i = 0;
        while (i < cmds.length) {
            String cmd = cmds[i++];
            if (cmd == PERM_PACKAGE) {
                pkg = cmds[i++];
                try {
                    pkgInfo = pm.getPackageInfo(pkg,
                            PackageManager.GET_PERMISSIONS
                            | PackageManager.GET_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException e) {
                    pkgInfo = null;
                }
            } else if (cmd == PERM_DEFINED || cmd == PERM_UNDEFINED
                    || cmd == PERM_USED || cmd == PERM_NOTUSED) {
                mode = cmds[i++];
            } else {
                if (mode == PERM_DEFINED) {
                    try {
                        PermissionInfo pi = pm.getPermissionInfo(cmd, 0);
                        assertNotNull(pi);
                        assertEquals(pi.packageName, pkg);
                        assertEquals(pi.name, cmd);
                        assertNotNull(pkgInfo);
                        boolean found = false;
                        for (int j=0; j<pkgInfo.permissions.length && !found; j++) {
                            if (pkgInfo.permissions[j].name.equals(cmd)) {
                                found = true;
                            }
                        }
                        if (!found) {
                            fail("Permission not found: " + cmd);
                        }
                    } catch (NameNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                } else if (mode == PERM_UNDEFINED) {
                    try {
                        pm.getPermissionInfo(cmd, 0);
                        throw new RuntimeException("Permission exists: " + cmd);
                    } catch (NameNotFoundException e) {
                    }
                    if (pkgInfo != null) {
                        boolean found = false;
                        for (int j=0; j<pkgInfo.permissions.length && !found; j++) {
                            if (pkgInfo.permissions[j].name.equals(cmd)) {
                                found = true;
                            }
                        }
                        if (found) {
                            fail("Permission still exists: " + cmd);
                        }
                    }
                } else if (mode == PERM_USED || mode == PERM_NOTUSED) {
                    boolean found = false;
                    for (int j=0; j<pkgInfo.requestedPermissions.length && !found; j++) {
                        if (pkgInfo.requestedPermissions[j].equals(cmd)) {
                            found = true;
                        }
                    }
                    if (!found) {
                        fail("Permission not requested: " + cmd);
                    }
                    if (mode == PERM_USED) {
                        if (pm.checkPermission(cmd, pkg)
                                != PackageManager.PERMISSION_GRANTED) {
                            fail("Permission not granted: " + cmd);
                        }
                    } else {
                        if (pm.checkPermission(cmd, pkg)
                                != PackageManager.PERMISSION_DENIED) {
                            fail("Permission granted: " + cmd);
                        }
                    }
                }
            }
        }
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install it.
     */
    private InstallParams installFromRawResource(String outFileName,
            int rawResId, int flags, boolean cleanUp, boolean fail, int result,
            int expInstallLocation) {
        PackageManager pm = mContext.getPackageManager();
        File filesDir = mContext.getFilesDir();
        File outFile = new File(filesDir, outFileName);
        Uri packageURI = getInstallablePackage(rawResId, outFile);
        PackageParser.Package pkg = parsePackage(packageURI);
        assertNotNull(pkg);
        if ((flags & PackageManager.INSTALL_REPLACE_EXISTING) == 0) {
            // Make sure the package doesn't exist
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg.packageName,
                        PackageManager.GET_UNINSTALLED_PACKAGES);
                GenericReceiver receiver = new DeleteReceiver(pkg.packageName);
                invokeDeletePackage(packageURI, 0,
                        pkg.packageName, receiver);
            } catch (NameNotFoundException e1) {
            } catch (Exception e) {
                failStr(e);
            }
        }
        InstallParams ip = null;
        try {
            if (fail) {
                assertTrue(invokeInstallPackageFail(packageURI, flags,
                        pkg.packageName, result));
                assertNotInstalled(pkg.packageName);
            } else {
                InstallReceiver receiver = new InstallReceiver(pkg.packageName);
                assertTrue(invokeInstallPackage(packageURI, flags,
                        pkg.packageName, receiver));
                // Verify installed information
                assertInstall(pkg, flags, expInstallLocation);
                ip = new InstallParams(pkg, outFileName, packageURI);
            }
            return ip;
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            if (cleanUp) {
                cleanUpInstall(ip);
            }
        }
        return ip;
    }

    public void testInstallNormalInternal() {
        sampleInstallFromRawResource(0, true);
    }

    public void testInstallFwdLockedInternal() {
        sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, true);
    }

    public void testInstallSdcard() {
        sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, true);
    }

    /* ------------------------- Test replacing packages --------------*/
    class ReplaceReceiver extends GenericReceiver {
        String pkgName;
        final static int INVALID = -1;
        final static int REMOVED = 1;
        final static int ADDED = 2;
        final static int REPLACED = 3;
        int removed = INVALID;
        // for updated system apps only
        boolean update = false;

        ReplaceReceiver(String pkgName) {
            this.pkgName = pkgName;
            filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            if (update) {
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
            }
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            Uri data = intent.getData();
            String installedPkg = data.getEncodedSchemeSpecificPart();
            if (pkgName == null || !pkgName.equals(installedPkg)) {
                return false;
            }
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                removed = REMOVED;
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (removed != REMOVED) {
                    return false;
                }
                boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (!replacing) {
                    return false;
                }
                removed = ADDED;
                if (!update) {
                    return true;
                }
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                if (removed != ADDED) {
                    return false;
                }
                removed = REPLACED;
                return true;
            }
            return false;
        }
    }

    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install first and then replace it
     * again.
     */
    public void replaceFromRawResource(int flags) {
        InstallParams ip = sampleInstallFromRawResource(flags, false);
        boolean replace = ((flags & PackageManager.INSTALL_REPLACE_EXISTING) != 0);
        Log.i(TAG, "replace=" + replace);
        GenericReceiver receiver;
        if (replace) {
            receiver = new ReplaceReceiver(ip.pkg.packageName);
            Log.i(TAG, "Creating replaceReceiver");
        } else {
            receiver = new InstallReceiver(ip.pkg.packageName);
        }
        try {
            try {
                assertEquals(invokeInstallPackage(ip.packageURI, flags,
                        ip.pkg.packageName, receiver), replace);
                if (replace) {
                    assertInstall(ip.pkg, flags, ip.pkg.installLocation);
                }
            } catch (Exception e) {
                failStr("Failed with exception : " + e);
            }
        } finally {
            cleanUpInstall(ip);
        }
    }

    public void testReplaceFailNormalInternal() {
        replaceFromRawResource(0);
    }

    public void testReplaceFailFwdLockedInternal() {
        replaceFromRawResource(PackageManager.INSTALL_FORWARD_LOCK);
    }

    public void testReplaceFailSdcard() {
        replaceFromRawResource(PackageManager.INSTALL_EXTERNAL);
    }

    public void testReplaceNormalInternal() {
        replaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING);
    }

    public void testReplaceFwdLockedInternal() {
        replaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING |
                PackageManager.INSTALL_FORWARD_LOCK);
    }

    public void testReplaceSdcard() {
        replaceFromRawResource(PackageManager.INSTALL_REPLACE_EXISTING |
                PackageManager.INSTALL_EXTERNAL);
    }

    /* -------------- Delete tests ---*/
    class DeleteObserver extends IPackageDeleteObserver.Stub {

        public boolean succeeded;
        private boolean doneFlag = false;

        public boolean isDone() {
            return doneFlag;
        }

        public void packageDeleted(boolean succeeded) throws RemoteException {
            synchronized(this) {
                this.succeeded = succeeded;
                doneFlag = true;
                notifyAll();
            }
        }
    }

    class DeleteReceiver extends GenericReceiver {
        String pkgName;

        DeleteReceiver(String pkgName) {
            this.pkgName = pkgName;
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                return false;
            }
            Uri data = intent.getData();
            String installedPkg = data.getEncodedSchemeSpecificPart();
            if (pkgName.equals(installedPkg)) {
                return true;
            }
            return false;
        }
    }

    public boolean invokeDeletePackage(Uri packageURI, int flags,
            final String pkgName, GenericReceiver receiver) throws Exception {
        DeleteObserver observer = new DeleteObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().deletePackage(pkgName, observer, flags);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for packageInstalled callback");
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for PACKAGE_REMOVED notification");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    public void deleteFromRawResource(int iFlags, int dFlags) {
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        boolean retainData = ((dFlags & PackageManager.DONT_DELETE_DATA) != 0);
        GenericReceiver receiver = new DeleteReceiver(ip.pkg.packageName);
        DeleteObserver observer = new DeleteObserver();
        try {
            assertTrue(invokeDeletePackage(ip.packageURI, dFlags,
                    ip.pkg.packageName, receiver));
            ApplicationInfo info = null;
            Log.i(TAG, "okay4");
            try {
            info = getPm().getApplicationInfo(ip.pkg.packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES);
            } catch (NameNotFoundException e) {
                info = null;
            }
            if (retainData) {
                assertNotNull(info);
                assertEquals(info.packageName, ip.pkg.packageName);
                File file = new File(info.dataDir);
                assertTrue(file.exists());
            } else {
                assertNull(info);
            }
        } catch (Exception e) {
            failStr(e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    public void testDeleteNormalInternal() {
        deleteFromRawResource(0, 0);
    }

    public void testDeleteFwdLockedInternal() {
        deleteFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, 0);
    }

    public void testDeleteSdcard() {
        deleteFromRawResource(PackageManager.INSTALL_EXTERNAL, 0);
    }

    public void testDeleteNormalInternalRetainData() {
        deleteFromRawResource(0, PackageManager.DONT_DELETE_DATA);
    }

    public void testDeleteFwdLockedInternalRetainData() {
        deleteFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, PackageManager.DONT_DELETE_DATA);
    }

    public void testDeleteSdcardRetainData() {
        deleteFromRawResource(PackageManager.INSTALL_EXTERNAL, PackageManager.DONT_DELETE_DATA);
    }

    /* sdcard mount/unmount tests ******/

    class SdMountReceiver extends GenericReceiver {
        String pkgNames[];
        boolean status = true;

        SdMountReceiver(String[] pkgNames) {
            this.pkgNames = pkgNames;
            IntentFilter filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            Log.i(TAG, "okay 1");
            String action = intent.getAction();
            if (!Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                return false;
            }
            String rpkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            for (String pkg : pkgNames) {
                boolean found = false;
                for (String rpkg : rpkgList) {
                    if (rpkg.equals(pkg)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    status = false;
                    return true;
                }
            }
            return true;
        }
    }

    class SdUnMountReceiver extends GenericReceiver {
        String pkgNames[];
        boolean status = true;

        SdUnMountReceiver(String[] pkgNames) {
            this.pkgNames = pkgNames;
            IntentFilter filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                return false;
            }
            String rpkgList[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            for (String pkg : pkgNames) {
                boolean found = false;
                for (String rpkg : rpkgList) {
                    if (rpkg.equals(pkg)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    status = false;
                    return true;
                }
            }
            return true;
        }
    }

    IMountService getMs() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get mount service");
        }
        return null;
    }

    boolean getMediaState() {
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getMs().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
        } catch (RemoteException e) {
            return false;
        }
    }

    boolean mountMedia() {
        if (getMediaState()) {
            return true;
        }
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        int ret = getMs().mountVolume(mPath);
        return ret == StorageResultCode.OperationSucceeded;
        } catch (RemoteException e) {
            return false;
        }
    }



    private boolean unmountMedia() {
        if (!getMediaState()) {
            return true;
        }
        String path = Environment.getExternalStorageDirectory().toString();
        StorageListener observer = new StorageListener();
        StorageManager sm = (StorageManager) mContext.getSystemService(Context.STORAGE_SERVICE);
        sm.registerListener(observer);
        try {
            // Wait on observer
            synchronized(observer) {
                getMs().unmountVolume(path, true);
                long waitTime = 0;
                while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    observer.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!observer.isDone()) {
                    throw new Exception("Timed out waiting for packageInstalled callback");
                }
                return true;
            }
        } catch (Exception e) {
            return false;
        } finally {
            sm.unregisterListener(observer);
        }
    }

    private boolean mountFromRawResource() {
        // Install pkg on sdcard
        InstallParams ip = sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, false);
        if (localLOGV) Log.i(TAG, "Installed pkg on sdcard");
        boolean origState = getMediaState();
        boolean registeredReceiver = false;
        SdMountReceiver receiver = new SdMountReceiver(new String[]{ip.pkg.packageName});
        try {
            if (localLOGV) Log.i(TAG, "Unmounting media");
            // Unmount media
            assertTrue(unmountMedia());
            if (localLOGV) Log.i(TAG, "Unmounted media");
            // Register receiver here
            PackageManager pm = getPm();
            mContext.registerReceiver(receiver, receiver.filter);
            registeredReceiver = true;

            // Wait on receiver
            synchronized (receiver) {
                if (localLOGV) Log.i(TAG, "Mounting media");
                // Mount media again
                assertTrue(mountMedia());
                if (localLOGV) Log.i(TAG, "Mounted media");
                if (localLOGV) Log.i(TAG, "Waiting for notification");
                long waitTime = 0;
                // Verify we received the broadcast
                waitTime = 0;
                while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                    receiver.wait(WAIT_TIME_INCR);
                    waitTime += WAIT_TIME_INCR;
                }
                if(!receiver.isDone()) {
                    failStr("Timed out waiting for EXTERNAL_APPLICATIONS notification");
                }
                return receiver.received;
            }
        } catch (InterruptedException e) {
            failStr(e);
            return false;
        } finally {
            if (registeredReceiver) mContext.unregisterReceiver(receiver);
            // Restore original media state
            if (origState) {
                mountMedia();
            } else {
                unmountMedia();
            }
            if (localLOGV) Log.i(TAG, "Cleaning up install");
            cleanUpInstall(ip);
        }
    }

    /*
     * Install package on sdcard. Unmount and then mount the media.
     * (Use PackageManagerService private api for now)
     * Make sure the installed package is available.
     * STOPSHIP will uncomment when MountService api's to mount/unmount
     * are made asynchronous.
     */
    public void xxxtestMountSdNormalInternal() {
        assertTrue(mountFromRawResource());
    }

    void cleanUpInstall(InstallParams ip) {
        if (ip == null) {
            return;
        }
        Runtime.getRuntime().gc();
        Log.i(TAG, "Deleting package : " + ip.pkg.packageName);
        getPm().deletePackage(ip.pkg.packageName, null, 0);
        File outFile = new File(ip.outFileName);
        if (outFile != null && outFile.exists()) {
            outFile.delete();
        }
    }

    public void testManifestInstallLocationInternal() {
        installFromRawResource("install.apk", R.raw.install_loc_internal,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
    }

    public void testManifestInstallLocationSdcard() {
        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    public void testManifestInstallLocationAuto() {
        installFromRawResource("install.apk", R.raw.install_loc_auto,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_AUTO);
    }

    public void testManifestInstallLocationUnspecified() {
        installFromRawResource("install.apk", R.raw.install_loc_unspecified,
                0, true, false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    public void testManifestInstallLocationFwdLockedFlagSdcard() {
        installFromRawResource("install.apk", R.raw.install_loc_unspecified,
                PackageManager.INSTALL_FORWARD_LOCK |
                PackageManager.INSTALL_EXTERNAL, true, true,
                PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
    }

    public void testManifestInstallLocationFwdLockedSdcard() {
        installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                PackageManager.INSTALL_FORWARD_LOCK, true, false,
                -1,
                PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
    }

    /*
     * Install a package on internal flash via PackageManager install flag. Replace
     * the package via flag to install on sdcard. Make sure the new flag overrides
     * the old install location.
     */
    public void testReplaceFlagInternalSdcard() {
        int iFlags = 0;
        int rFlags = PackageManager.INSTALL_EXTERNAL;
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            assertEquals(invokeInstallPackage(ip.packageURI, replaceFlags,
                    ip.pkg.packageName, receiver), true);
            assertInstall(ip.pkg, rFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    /*
     * Install a package on sdcard via PackageManager install flag. Replace
     * the package with no flags or manifest option and make sure the old
     * install location is retained.
     */
    public void testReplaceFlagSdcardInternal() {
        int iFlags = PackageManager.INSTALL_EXTERNAL;
        int rFlags = 0;
        InstallParams ip = sampleInstallFromRawResource(iFlags, false);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            assertEquals(invokeInstallPackage(ip.packageURI, replaceFlags,
                    ip.pkg.packageName, receiver), true);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    public void testManifestInstallLocationReplaceInternalSdcard() {
        int iFlags = 0;
        int iApk = R.raw.install_loc_internal;
        int rFlags = 0;
        int rApk = R.raw.install_loc_sdcard;
        InstallParams ip = installFromRawResource("install.apk", iApk,
                iFlags, false,
                false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        GenericReceiver receiver = new ReplaceReceiver(ip.pkg.packageName);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            InstallParams rp = installFromRawResource("install.apk", rApk,
                    replaceFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
            assertInstall(rp.pkg, replaceFlags, rp.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    public void testManifestInstallLocationReplaceSdcardInternal() {
        int iFlags = 0;
        int iApk = R.raw.install_loc_sdcard;
        int rFlags = 0;
        int rApk = R.raw.install_loc_unspecified;
        InstallParams ip = installFromRawResource("install.apk", iApk,
                iFlags, false,
                false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
        int replaceFlags = rFlags | PackageManager.INSTALL_REPLACE_EXISTING;
        try {
            InstallParams rp = installFromRawResource("install.apk", rApk,
                    replaceFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
            assertInstall(rp.pkg, replaceFlags, ip.pkg.installLocation);
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
        }
    }

    class MoveReceiver extends GenericReceiver {
        String pkgName;
        final static int INVALID = -1;
        final static int REMOVED = 1;
        final static int ADDED = 2;
        int removed = INVALID;

        MoveReceiver(String pkgName) {
            this.pkgName = pkgName;
            filter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            super.setFilter(filter);
        }

        public boolean notifyNow(Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "MoveReceiver::" + action);
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                String[] list = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (list != null) {
                    for (String pkg : list) {
                        if (pkg.equals(pkgName)) {
                            removed = REMOVED;
                            break;
                        }
                    }
                }
                removed = REMOVED;
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                if (removed != REMOVED) {
                    return false;
                }
                String[] list = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                if (list != null) {
                    for (String pkg : list) {
                        if (pkg.equals(pkgName)) {
                            removed = ADDED;
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private class PackageMoveObserver extends IPackageMoveObserver.Stub {
        public int returnCode;
        private boolean doneFlag = false;

        public void packageMoved(String packageName, int returnCode) {
            synchronized(this) {
                this.returnCode = returnCode;
                doneFlag = true;
                notifyAll();
            }
        }

        public boolean isDone() {
            return doneFlag;
        }
    }

    public boolean invokeMovePackage(String pkgName, int flags,
            GenericReceiver receiver) throws Exception {
        PackageMoveObserver observer = new PackageMoveObserver();
        final boolean received = false;
        mContext.registerReceiver(receiver, receiver.filter);
        try {
            // Wait on observer
            synchronized(observer) {
                synchronized (receiver) {
                    getPm().movePackage(pkgName, observer, flags);
                    long waitTime = 0;
                    while((!observer.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        observer.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!observer.isDone()) {
                        throw new Exception("Timed out waiting for pkgmove callback");
                    }
                    if (observer.returnCode != PackageManager.MOVE_SUCCEEDED) {
                        return false;
                    }
                    // Verify we received the broadcast
                    waitTime = 0;
                    while((!receiver.isDone()) && (waitTime < MAX_WAIT_TIME) ) {
                        receiver.wait(WAIT_TIME_INCR);
                        waitTime += WAIT_TIME_INCR;
                    }
                    if(!receiver.isDone()) {
                        throw new Exception("Timed out waiting for MOVE notifications");
                    }
                    return receiver.received;
                }
            }
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    private int getInstallLoc() {
        boolean userSetting = false;
        int origDefaultLoc = PackageInfo.INSTALL_LOCATION_AUTO;
        try {
            userSetting = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SET_INSTALL_LOCATION) != 0;
            origDefaultLoc = Settings.System.getInt(mContext.getContentResolver(), Settings.System.DEFAULT_INSTALL_LOCATION);
        } catch (SettingNotFoundException e1) {
        }
        return origDefaultLoc;
    }

    private void setInstallLoc(int loc) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.DEFAULT_INSTALL_LOCATION, loc);
    }
    /*
     * Utility function that reads a apk bundled as a raw resource
     * copies it into own data directory and invokes
     * PackageManager api to install first and then replace it
     * again.
     */
    public void moveFromRawResource(int installFlags, int moveFlags,
            int expRetCode) {
        int origDefaultLoc = getInstallLoc();
        setInstallLoc(PackageHelper.APP_INSTALL_AUTO);
        // Install first
        InstallParams ip = sampleInstallFromRawResource(installFlags, false);
        ApplicationInfo oldAppInfo = null;
        try {
            oldAppInfo = getPm().getApplicationInfo(ip.pkg.packageName, 0);
        } catch (NameNotFoundException e) {
            failStr("Pkg hasnt been installed correctly");
        }

        // Create receiver based on expRetCode
        MoveReceiver receiver = new MoveReceiver(ip.pkg.packageName);
        try {
            boolean retCode = invokeMovePackage(ip.pkg.packageName, moveFlags,
                    receiver);
            if (expRetCode == PackageManager.MOVE_SUCCEEDED) {
                assertTrue(retCode);
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.packageName, 0);
                assertNotNull(info);
                if ((moveFlags & PackageManager.MOVE_INTERNAL) != 0) {
                    assertTrue((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0);
                } else if ((moveFlags & PackageManager.MOVE_EXTERNAL_MEDIA) != 0){
                    assertTrue((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0);
                }
            } else {
                assertFalse(retCode);
                ApplicationInfo info = getPm().getApplicationInfo(ip.pkg.packageName, 0);
                assertNotNull(info);
                assertEquals(oldAppInfo.flags, info.flags);
            }
        } catch (Exception e) {
            failStr("Failed with exception : " + e);
        } finally {
            cleanUpInstall(ip);
            // Restore default install location
            setInstallLoc(origDefaultLoc);
        }
    }

    public void testMoveAppInternalToExternal() {
        moveFromRawResource(0, PackageManager.MOVE_EXTERNAL_MEDIA,
                PackageManager.MOVE_SUCCEEDED);
    }

    public void testMoveAppInternalToInternal() {
        moveFromRawResource(0, PackageManager.MOVE_INTERNAL,
                PackageManager.MOVE_FAILED_INVALID_LOCATION);
    }

    public void testMoveAppExternalToExternal() {
        moveFromRawResource(PackageManager.INSTALL_EXTERNAL, PackageManager.MOVE_EXTERNAL_MEDIA,
                PackageManager.MOVE_FAILED_INVALID_LOCATION);
    }
    public void testMoveAppExternalToInternal() {
        moveFromRawResource(PackageManager.INSTALL_EXTERNAL, PackageManager.MOVE_INTERNAL,
                PackageManager.MOVE_SUCCEEDED);
    }

    /*
     * Test that an install error code is returned when media is unmounted
     * and package installed on sdcard via package manager flag.
     */
    public void testInstallSdcardUnmount() {
        boolean origState = getMediaState();
        try {
            // Unmount sdcard
            assertTrue(unmountMedia());
            // Try to install and make sure an error code is returned.
            assertNull(installFromRawResource("install.apk", R.raw.install,
                    PackageManager.INSTALL_EXTERNAL, false,
                    true, PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    PackageInfo.INSTALL_LOCATION_AUTO));
        } finally {
            // Restore original media state
            if (origState) {
                mountMedia();
            } else {
                unmountMedia();
            }
        }
    }

    /*
    * Unmount sdcard. Try installing an app with manifest option to install
    * on sdcard. Make sure it gets installed on internal flash.
    */
   public void testInstallManifestSdcardUnmount() {
       boolean origState = getMediaState();
       try {
           // Unmount sdcard
           assertTrue(unmountMedia());
           // Try to install and make sure an error code is returned.
           assertNotNull(installFromRawResource("install.apk", R.raw.install_loc_sdcard,
                   0, false,
                   false, -1,
                   PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY));
       } finally {
           // Restore original media state
           if (origState) {
               mountMedia();
           } else {
               unmountMedia();
           }
       }
   }

   /*---------- Recommended install location tests ----*/
   /* Precedence: FlagManifestExistingUser
    * PrecedenceSuffixes:
    * Flag : FlagI, FlagE, FlagF
    * I - internal, E - external, F - forward locked, Flag suffix absent if not using any option.
    * Manifest: ManifestI, ManifestE, ManifestA, Manifest suffix absent if not using any option.
    * Existing: Existing suffix absent if not existing.
    * User: UserI, UserE, UserA, User suffix absent if not existing. 
    * 
    */
   /*
    * Install an app on internal flash
    */
   public void testFlagI() {
       sampleInstallFromRawResource(PackageManager.INSTALL_INTERNAL, true);
   }
   /*
    * Install an app on sdcard.
    */
   public void testFlagE() {
       sampleInstallFromRawResource(PackageManager.INSTALL_EXTERNAL, true);
   }

   /*
    * Install an app on sdcard.
    */
   public void testFlagF() {
       sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK, true);
   }
   /*
    * Install an app with both internal and external flags set. should fail
    */
   public void testFlagIE() {
       installFromRawResource("install.apk", R.raw.install,
               PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_INTERNAL,
               false,
               true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
               PackageInfo.INSTALL_LOCATION_AUTO);
   }

   /*
    * Install an app with both internal and external flags set. should fail
    */
   public void testFlagIF() {
       sampleInstallFromRawResource(PackageManager.INSTALL_FORWARD_LOCK |
               PackageManager.INSTALL_INTERNAL, true);
   }
   /*
    * Install an app with both internal and external flags set. should fail
    */
   public void testFlagEF() {
       installFromRawResource("install.apk", R.raw.install,
               PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_EXTERNAL,
               false,
               true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
               PackageInfo.INSTALL_LOCATION_AUTO);
   }
   /*
    * Install an app with both internal and external flags set. should fail
    */
   public void testFlagIEF() {
       installFromRawResource("install.apk", R.raw.install,
               PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_INTERNAL |
               PackageManager.INSTALL_EXTERNAL,
               false,
               true, PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION,
               PackageInfo.INSTALL_LOCATION_AUTO);
   }
   /*
    * Install an app with both internal and manifest option set.
    * should install on internal.
    */
   public void testFlagIManifestI() {
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both internal and manifest preference for
    * preferExternal. Should install on internal.
    */
   public void testFlagIManifestE() {
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both internal and manifest preference for
    * auto. should install internal.
    */
   public void testFlagIManifestA() {
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               PackageManager.INSTALL_INTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * Install an app with both external and manifest option set.
    * should install externally.
    */
   public void testFlagEManifestI() {
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * Install an app with both external and manifest preference for
    * preferExternal. Should install externally.
    */
   public void testFlagEManifestE() {
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * Install an app with both external and manifest preference for
    * auto. should install on external media.
    */
   public void testFlagEManifestA() {
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * Install an app with fwd locked flag set and install location set to
    * internal. should install internally.
    */
   public void testFlagFManifestI() {
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * Install an app with fwd locked flag set and install location set to
    * preferExternal. should install internally.
    */
   public void testFlagFManifestE() {
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * Install an app with fwd locked flag set and install location set to
    * auto. should install internally.
    */
   public void testFlagFManifestA() {
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               PackageManager.INSTALL_EXTERNAL,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /* The following test functions verify install location for existing apps.
    * ie existing app can be installed internally or externally. If install
    * flag is explicitly set it should override current location. If manifest location
    * is set, that should over ride current location too. if not the existing install
    * location should be honoured.
    * testFlagI/E/F/ExistingI/E - 
    */
   public void testFlagIExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_INTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   public void testFlagIExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_INTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   public void testFlagEExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   public void testFlagEExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_EXTERNAL | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   public void testFlagFExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   public void testFlagFExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_FORWARD_LOCK | PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install,
               rFlags,
               true,
               false, -1,
               -1);
   }
   /*
    * The following set of tests verify the installation of apps with
    * install location attribute set to internalOnly, preferExternal and auto.
    * The manifest option should dictate the install location.
    * public void testManifestI/E/A
    * TODO out of memory fall back behaviour.
    */
   public void testManifestI() {
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               0,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testManifestE() {
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               0,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   public void testManifestA() {
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               0,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   /*
    * The following set of tests verify the installation of apps
    * with install location attribute set to internalOnly, preferExternal and auto
    * for already existing apps. The manifest option should take precedence.
    * TODO add out of memory fall back behaviour.
    * testManifestI/E/AExistingI/E 
    */
   public void testManifestIExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testManifestIExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_internal,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testManifestEExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   public void testManifestEExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_sdcard,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   public void testManifestAExistingI() {
       int iFlags = PackageManager.INSTALL_INTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_AUTO);
   }
   public void testManifestAExistingE() {
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               -1);
       // Replace now
       installFromRawResource("install.apk", R.raw.install_loc_auto,
               rFlags,
               true,
               false, -1,
               PackageInfo.INSTALL_LOCATION_AUTO);
   }
   /*
    * The following set of tests check install location for existing
    * application based on user setting.
    */
   private int getExpectedInstallLocation(int userSetting) {
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       boolean enable = getUserSettingSetInstallLocation();
       if (enable) {
           if (userSetting == PackageHelper.APP_INSTALL_AUTO) {
               iloc = PackageInfo.INSTALL_LOCATION_AUTO;
           } else if (userSetting == PackageHelper.APP_INSTALL_EXTERNAL) {
               iloc = PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL;
           } else if (userSetting == PackageHelper.APP_INSTALL_INTERNAL) {
               iloc = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
           }
       }
       return iloc;
   }
   private void setExistingXUserX(int userSetting, int iFlags, int iloc) {
       int rFlags = PackageManager.INSTALL_REPLACE_EXISTING;
       // First install.
       installFromRawResource("install.apk", R.raw.install,
               iFlags,
               false,
               false, -1,
               PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
       int origSetting = getInstallLoc();
       try {
           // Set user setting
           setInstallLoc(userSetting);
           // Replace now
           installFromRawResource("install.apk", R.raw.install,
                   rFlags,
                   true,
                   false, -1,
                   iloc);
       } finally {
           setInstallLoc(origSetting);
       }
   }
   public void testExistingIUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iFlags = PackageManager.INSTALL_INTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testExistingIUserE() {
       int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
       int iFlags = PackageManager.INSTALL_INTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testExistingIUserA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iFlags = PackageManager.INSTALL_INTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
   }
   public void testExistingEUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   public void testExistingEUserE() {
       int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   public void testExistingEUserA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iFlags = PackageManager.INSTALL_EXTERNAL;
       setExistingXUserX(userSetting, iFlags, PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL);
   }
   /*
    * The following set of tests verify that the user setting defines
    * the install location.
    * 
    */
   private boolean getUserSettingSetInstallLocation() {
       try {
           return Settings.System.getInt(mContext.getContentResolver(), Settings.System.SET_INSTALL_LOCATION) != 0;
           
       } catch (SettingNotFoundException e1) {
       }
       return false;
   }

   private void setUserSettingSetInstallLocation(boolean value) {
       Settings.System.putInt(mContext.getContentResolver(),
               Settings.System.SET_INSTALL_LOCATION, value ? 1 : 0);
   }
   private void setUserX(boolean enable, int userSetting, int iloc) {
       boolean origUserSetting = getUserSettingSetInstallLocation();
       int origSetting = getInstallLoc();
       try {
           setUserSettingSetInstallLocation(enable);
           // Set user setting
           setInstallLoc(userSetting);
           // Replace now
           installFromRawResource("install.apk", R.raw.install,
                   0,
                   true,
                   false, -1,
                   iloc);
       } finally {
           // Restore original setting
           setUserSettingSetInstallLocation(origUserSetting);
           setInstallLoc(origSetting);
       }
   }
   public void testUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iloc = getExpectedInstallLocation(userSetting);
       setUserX(true, userSetting, iloc);
   }
   public void testUserE() {
       int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
       int iloc = getExpectedInstallLocation(userSetting);
       setUserX(true, userSetting, iloc);
   }
   public void testUserA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iloc = getExpectedInstallLocation(userSetting);
       setUserX(true, userSetting, iloc);
   }
   /*
    * The following set of tests turn on/off the basic
    * user setting for turning on install location.
    */
   public void testUserPrefOffUserI() {
       int userSetting = PackageHelper.APP_INSTALL_INTERNAL;
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       setUserX(false, userSetting, iloc);
   }
   public void testUserPrefOffUserE() {
       int userSetting = PackageHelper.APP_INSTALL_EXTERNAL;
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       setUserX(false, userSetting, iloc);
   }
   public void testUserPrefOffA() {
       int userSetting = PackageHelper.APP_INSTALL_AUTO;
       int iloc = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
       setUserX(false, userSetting, iloc);
   }
   
    static final String BASE_PERMISSIONS_DEFINED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_decl_perm",
        PERM_DEFINED,
        "com.android.unit_tests.NORMAL",
        "com.android.unit_tests.DANGEROUS",
        "com.android.unit_tests.SIGNATURE",
    };
    
    static final String BASE_PERMISSIONS_UNDEFINED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_decl_perm",
        PERM_UNDEFINED,
        "com.android.unit_tests.NORMAL",
        "com.android.unit_tests.DANGEROUS",
        "com.android.unit_tests.SIGNATURE",
    };
    
    static final String BASE_PERMISSIONS_USED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_use_perm_good",
        PERM_USED,
        "com.android.unit_tests.NORMAL",
        "com.android.unit_tests.DANGEROUS",
        "com.android.unit_tests.SIGNATURE",
    };
    
    static final String BASE_PERMISSIONS_NOTUSED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_use_perm_good",
        PERM_NOTUSED,
        "com.android.unit_tests.NORMAL",
        "com.android.unit_tests.DANGEROUS",
        "com.android.unit_tests.SIGNATURE",
    };
    
    static final String BASE_PERMISSIONS_SIGUSED[] = new String[] {
        PERM_PACKAGE, "com.android.unit_tests.install_use_perm_good",
        PERM_USED,
        "com.android.unit_tests.SIGNATURE",
        PERM_NOTUSED,
        "com.android.unit_tests.NORMAL",
        "com.android.unit_tests.DANGEROUS",
    };
    
    /*
     * Ensure that permissions are properly declared.
     */
    public void testInstallDeclaresPermissions() {
        InstallParams ip = null;
        InstallParams ip2 = null;
        try {
            // **: Upon installing a package, are its declared permissions published?
           
            int iFlags = PackageManager.INSTALL_INTERNAL;
            int iApk = R.raw.install_decl_perm;
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
           
            // **: Upon installing package, are its permissions granted?
           
            int i2Flags = PackageManager.INSTALL_INTERNAL;
            int i2Apk = R.raw.install_use_perm_good;
            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_USED);
            
            // **: Upon removing but not deleting, are permissions retained?
           
            GenericReceiver receiver = new DeleteReceiver(ip.pkg.packageName);
           
            try {
                invokeDeletePackage(ip.packageURI, PackageManager.DONT_DELETE_DATA,
                        ip.pkg.packageName, receiver);
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);
           
            // **: Upon re-installing, are permissions retained?
           
            ip = installFromRawResource("install.apk", iApk,
                    iFlags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_USED);
           
            // **: Upon deleting package, are all permissions removed?
           
            try {
                invokeDeletePackage(ip.packageURI, 0,
                        ip.pkg.packageName, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);
           
            // **: Delete package using permissions; nothing to check here.
           
            GenericReceiver receiver2 = new DeleteReceiver(ip2.pkg.packageName);
            try {
                invokeDeletePackage(ip2.packageURI, 0,
                        ip2.pkg.packageName, receiver);
                ip2 = null;
            } catch (Exception e) {
                failStr(e);
            }
            
            // **: Re-install package using permissions; no permissions can be granted.
           
            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);
           
            // **: Upon installing declaring package, are sig permissions granted
            // to other apps (but not other perms)?
           
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            assertPermissions(BASE_PERMISSIONS_SIGUSED);
           
            // **: Re-install package using permissions; are all permissions granted?
            
            ip2 = installFromRawResource("install2.apk", i2Apk,
                    i2Flags | PackageManager.INSTALL_REPLACE_EXISTING, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip2.pkg, i2Flags, ip2.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);
           
            // **: Upon deleting package, are all permissions removed?
            
            try {
                invokeDeletePackage(ip.packageURI, 0,
                        ip.pkg.packageName, receiver);
                ip = null;
            } catch (Exception e) {
                failStr(e);
            }
            assertPermissions(BASE_PERMISSIONS_UNDEFINED);
            assertPermissions(BASE_PERMISSIONS_NOTUSED);
            
            // **: Delete package using permissions; nothing to check here.
            
            try {
                invokeDeletePackage(ip2.packageURI, 0,
                        ip2.pkg.packageName, receiver);
                ip2 = null;
            } catch (Exception e) {
                failStr(e);
            }
            
        } finally {
            if (ip2 != null) {
                cleanUpInstall(ip2);
            }
            if (ip != null) {
                cleanUpInstall(ip);
            }
        }
    }

    /*
     * Ensure that permissions are properly declared.
     */
    public void testInstallOnSdPermissionsUnmount() {
        InstallParams ip = null;
        boolean origMediaState = getMediaState();
        try {
            // **: Upon installing a package, are its declared permissions published?
            int iFlags = PackageManager.INSTALL_INTERNAL;
            int iApk = R.raw.install_decl_perm;
            ip = installFromRawResource("install.apk", iApk,
                    iFlags, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
            assertInstall(ip.pkg, iFlags, ip.pkg.installLocation);
            assertPermissions(BASE_PERMISSIONS_DEFINED);
            // Unmount media here
            assertTrue(unmountMedia());
            // Mount media again
            mountMedia();
            //Check permissions now
            assertPermissions(BASE_PERMISSIONS_DEFINED);
        } finally {
            if (ip != null) {
                cleanUpInstall(ip);
            }
        }
    }

    /* This test creates a stale container via MountService and then installs
     * a package and verifies that the stale container is cleaned up and install
     * is successful.
     * Please note that this test is very closely tied to the framework's
     * naming convention for secure containers.
     */
    public void testInstallSdcardStaleContainer() {
        boolean origMediaState = getMediaState();
        try {
            String outFileName = "install.apk";
            int rawResId = R.raw.install;
            PackageManager pm = mContext.getPackageManager();
            File filesDir = mContext.getFilesDir();
            File outFile = new File(filesDir, outFileName);
            Uri packageURI = getInstallablePackage(rawResId, outFile);
            PackageParser.Package pkg = parsePackage(packageURI);
            assertNotNull(pkg);
            // Install an app on sdcard.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_EXTERNAL, false,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
            // Unmount sdcard
            unmountMedia();
            // Delete the app on sdcard to leave a stale container on sdcard.
            GenericReceiver receiver = new DeleteReceiver(pkg.packageName);
            assertTrue(invokeDeletePackage(packageURI, 0, pkg.packageName, receiver));
            mountMedia();
            // Reinstall the app and make sure it gets installed.
            installFromRawResource(outFileName, rawResId,
                    PackageManager.INSTALL_EXTERNAL, true,
                    false, -1, PackageInfo.INSTALL_LOCATION_UNSPECIFIED);
        } catch (Exception e) { 
            failStr(e.getMessage());
        } finally {
            if (origMediaState) {
                mountMedia();
            } else {
                unmountMedia();
            }

        }
    }
    /*---------- Recommended install location tests ----*/
    /*
     * TODO's
     * check version numbers for upgrades
     * check permissions of installed packages
     * how to do tests on updated system apps?
     * verify updates to system apps cannot be installed on the sdcard.
     */
}