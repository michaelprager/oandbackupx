/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.schedules;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.util.Log;

import com.machiav3lli.backup.Constants;
import com.machiav3lli.backup.R;
import com.machiav3lli.backup.activities.MainActivityX;
import com.machiav3lli.backup.activities.SchedulerActivityX;
import com.machiav3lli.backup.handler.BackendController;
import com.machiav3lli.backup.handler.BackupRestoreHelper;
import com.machiav3lli.backup.handler.NotificationHelper;
import com.machiav3lli.backup.items.ActionResult;
import com.machiav3lli.backup.items.AppInfoX;
import com.machiav3lli.backup.schedules.db.Schedule;
import com.machiav3lli.backup.utils.FileUtils;
import com.machiav3lli.backup.utils.LogUtils;
import com.machiav3lli.backup.utils.PrefUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class HandleScheduledBackups {
    private static final String TAG = Constants.classTag(".HandleScheduledBackups");

    private final Context context;
    private final PowerManager powerManager;
    private final SharedPreferences prefs;
    private final List<BackupRestoreHelper.OnBackupRestoreListener> listeners;

    public HandleScheduledBackups(Context context) {
        this.context = context;
        this.prefs = PrefUtils.getDefaultSharedPreferences(context);
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.listeners = new ArrayList<>();
    }

    public void setOnBackupListener(BackupRestoreHelper.OnBackupRestoreListener listener) {
        this.listeners.add(listener);
    }

    public void initiateBackup(final int id, final Schedule.Mode mode, final int subMode, final boolean excludeSystem, final boolean enableCustomList) {
        new Thread(() -> {
            int notificationId = (int) System.currentTimeMillis();
            NotificationHelper.showNotification(this.context, MainActivityX.class, notificationId, this.context.getString(R.string.fetching_backup_list), "", true);
            List<AppInfoX> list;
            try {
                list = BackendController.getApplicationList(this.context);
            } catch (FileUtils.BackupLocationIsAccessibleException | PrefUtils.StorageLocationNotConfiguredException e) {
                Log.e(TAG, String.format("Scheduled backup failed due to %s: %s", e.getClass().getSimpleName(), e));
                try {
                    LogUtils logUtils = new LogUtils(this.context);
                    logUtils.writeToLogFile(e.toString());
                } catch (IOException | PrefUtils.StorageLocationNotConfiguredException | FileUtils.BackupLocationIsAccessibleException ex) {
                    ex.printStackTrace();
                }
                return;
            }
            Predicate<AppInfoX> predicate;
            Set<String> selectedPackages = CustomPackageList.getScheduleCustomList(this.context, id);
            Predicate<String> inCustomList = packageName -> !enableCustomList || selectedPackages.contains(packageName);
            switch (mode) {
                case USER:
                    predicate = appInfoX -> appInfoX.isInstalled() && !appInfoX.isSystem() && inCustomList.test(appInfoX.getPackageName());
                    break;
                case SYSTEM:
                    predicate = appInfoX -> appInfoX.isInstalled() && appInfoX.isSystem() && inCustomList.test(appInfoX.getPackageName());
                    break;
                case NEW_UPDATED:
                    predicate = appInfoX -> appInfoX.isInstalled() && (!excludeSystem || !appInfoX.isSystem())
                            && (!appInfoX.hasBackups() || appInfoX.isUpdated())
                            && inCustomList.test(appInfoX.getPackageName());
                    break;
                default: // equal to ALL
                    predicate = appInfoX -> inCustomList.test(appInfoX.getPackageName());
            }
            List<AppInfoX> listToBackUp = list.stream()
                    .filter(predicate)
                    .collect(Collectors.toList());
            startScheduledBackup(listToBackUp, subMode, notificationId);
        }).start();
    }

    public void startScheduledBackup(final List<AppInfoX> backupList, final int subMode, int notificationId) {
        if (PrefUtils.checkStoragePermissions(this.context)) {
            new Thread(() -> {
                Log.i(TAG, "Starting scheduled backup for " + backupList.size() + " items");
                PowerManager.WakeLock wl = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
                if (prefs.getBoolean("acquireWakelock", true)) {
                    wl.acquire(60 * 60 * 1000L /*60 minutes*/);
                    Log.i(TAG, "wakelock acquired");
                }
                int totalOfActions = backupList.size();
                int i = 1;
                BlacklistsDBHelper blacklistsDBHelper = new BlacklistsDBHelper(this.context);
                SQLiteDatabase db = blacklistsDBHelper.getReadableDatabase();
                List<String> blacklistedPackages = blacklistsDBHelper.getBlacklistedPackages(db, SchedulerActivityX.GLOBALBLACKLISTID);
                List<ActionResult> results = new ArrayList<>(totalOfActions);
                for (AppInfoX appInfo : backupList) {
                    if (blacklistedPackages.contains(appInfo.getPackageName())) {
                        Log.i(TAG, String.format("%s ignored",
                                appInfo.getPackageName()));
                        i++;
                        continue;
                    }
                    String title = context.getString(R.string.backupProgress) + " (" + i + "/" + totalOfActions + ")";
                    NotificationHelper.showNotification(this.context, MainActivityX.class, notificationId, title, appInfo.getPackageLabel(), false);
                    final BackupRestoreHelper backupRestoreHelper = new BackupRestoreHelper();
                    ActionResult result = backupRestoreHelper.backup(this.context, MainActivityX.getShellHandlerInstance(), appInfo, subMode);
                    results.add(result);
                    i++;
                }
                if (wl.isHeld()) {
                    wl.release();
                    Log.i(TAG, "wakelock released");
                }
                // Calculate the overall result
                String errors = results.stream()
                        .map(ActionResult::getMessage)
                        .filter(msg -> !msg.isEmpty())
                        .collect(Collectors.joining("\n"));
                ActionResult overallResult = new ActionResult(null, null, errors, results.parallelStream().anyMatch(ar -> ar.succeeded));

                // Update the notification
                String notificationTitle = overallResult.succeeded ? this.context.getString(R.string.batchSuccess) : context.getString(R.string.batchFailure);
                String notificationMessage = this.context.getString(R.string.sched_notificationMessage);
                NotificationHelper.showNotification(this.context, MainActivityX.class, notificationId, notificationTitle, notificationMessage, true);

                if (!overallResult.succeeded) {
                    try {
                        LogUtils logUtils = new LogUtils(this.context);
                        logUtils.writeToLogFile(errors);
                    } catch (IOException | PrefUtils.StorageLocationNotConfiguredException | FileUtils.BackupLocationIsAccessibleException e) {
                        e.printStackTrace();
                    }
                }
                for (BackupRestoreHelper.OnBackupRestoreListener l : listeners)
                    l.onBackupRestoreDone();
                blacklistsDBHelper.close();
            }).start();
        }
    }
}
