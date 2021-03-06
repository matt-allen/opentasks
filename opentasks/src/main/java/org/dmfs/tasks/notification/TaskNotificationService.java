/*
 * Copyright 2017 dmfs GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dmfs.tasks.notification;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.app.JobIntentService;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.support.v4.app.NotificationManagerCompat;

import org.dmfs.android.contentpal.Projection;
import org.dmfs.android.contentpal.predicates.AnyOf;
import org.dmfs.android.contentpal.predicates.EqArg;
import org.dmfs.android.contentpal.projections.Composite;
import org.dmfs.android.contentpal.rowsets.QueryRowSet;
import org.dmfs.android.contentpal.views.Sorted;
import org.dmfs.jems.iterable.composite.Diff;
import org.dmfs.jems.iterable.decorators.Mapped;
import org.dmfs.jems.pair.Pair;
import org.dmfs.opentaskspal.readdata.Id;
import org.dmfs.opentaskspal.readdata.TaskPin;
import org.dmfs.opentaskspal.readdata.TaskVersion;
import org.dmfs.opentaskspal.views.TasksView;
import org.dmfs.optional.Optional;
import org.dmfs.provider.tasks.AuthorityUtil;
import org.dmfs.tasks.JobIds;
import org.dmfs.tasks.R;
import org.dmfs.tasks.actions.utils.NotificationPrefs;
import org.dmfs.tasks.contract.TaskContract.Tasks;
import org.dmfs.tasks.model.ContentSet;
import org.dmfs.tasks.notification.state.PrefState;
import org.dmfs.tasks.notification.state.RowState;
import org.dmfs.tasks.notification.state.TaskNotificationState;
import org.dmfs.tasks.utils.In;

import java.util.ArrayList;


/**
 * A {@link Service} that triggers and updates {@link Notification}s for Due and Start alarms as well as pinned tasks.
 *
 * @author Tobias Reinsch <tobias@dmfs.org>
 */
public class TaskNotificationService extends JobIntentService
{

    public static void enqueueWork(@NonNull Context context, @NonNull Intent work)
    {
        enqueueWork(context, TaskNotificationService.class, JobIds.NOTIFICATION_SERVICE, work);
    }


    private SharedPreferences mNotificationPrefs;


    @Override
    public void onCreate()
    {
        super.onCreate();
        mNotificationPrefs = new NotificationPrefs(this).next();
    }


    @Override
    protected void onHandleWork(@NonNull Intent intent)
    {
        switch (intent.getAction())
        {
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case Intent.ACTION_BOOT_COMPLETED:
                /*
                 When the device boots up or the app has been updated we just repost all notifications.
                 */
                for (String uri : mNotificationPrefs.getAll().keySet())
                {
                    ActionService.startAction(this, ActionService.ACTION_RENOTIFY, Uri.parse(uri));
                }
                break;
            default:
                /*
                 * In any other case we synchronize our stored state with the database.
                 *
                 * Notifications of tasks which no longer exist are removed.
                 * Notifications of tasks which have been pinned are added.
                 * Notifications of tasks which have been unpinned are removed.
                 * Notifications of tasks which have changed otherwise ae updated.
                 */
                String authority = getString(R.string.opentasks_authority);

                Iterable<TaskNotificationState> currentNotifications = new org.dmfs.tasks.utils.Sorted<>(
                        (o, o2) -> (int) (ContentUris.parseId(o.task()) - ContentUris.parseId(o2.task())),
                        new Mapped<>(
                                PrefState::new,
                                mNotificationPrefs.getAll().entrySet()));

                for (Pair<Optional<TaskNotificationState>, Optional<RowState>> diff : new Diff<>(
                        currentNotifications,
                        new Mapped<>(snapShot -> new RowState(authority, snapShot.values()),
                                new QueryRowSet<>(
                                        new Sorted<>(Tasks._ID, new TasksView(authority, getContentResolver().acquireContentProviderClient(authority))),
                                        new Composite<>((Projection<Tasks>) Id.PROJECTION, TaskVersion.PROJECTION, TaskPin.PROJECTION),
                                        new AnyOf(
                                                new EqArg(Tasks.PINNED, 1),
                                                new In(Tasks._ID, new Mapped<>(p -> ContentUris.parseId(p.task()), currentNotifications))))),
                        // NOTE due to a bug in diff, the logic is currently reversed
                        (o, o2) -> (int) (ContentUris.parseId(o2.task()) - ContentUris.parseId(o.task()))))
                {
                    if (!diff.left().isPresent())
                    {
                        // new task not notified yet, must be pinned
                        ActionService.startAction(this, ActionService.ACTION_RENOTIFY, diff.right().value().task());
                    }
                    else if (!diff.right().isPresent())
                    {
                        // task no longer present, remove notification
                        removeTaskNotification(diff.left().value().task());
                    }
                    else
                    {
                        if (diff.left().value().taskVersion() != diff.right().value().taskVersion())
                        {
                            /*
                             * The task has been modified. If it's pinned we update it.
                             * Otherwise we remove it if it was pinned before.
                             */
                            if (diff.right().value().ongoing())
                            {
                                ActionService.startAction(this, ActionService.ACTION_RENOTIFY, diff.left().value().task());
                            }
                            else if (diff.left().value().ongoing())
                            {
                                // task has been unpinned
                                removeTaskNotification(diff.left().value().task());
                            }
                        }
                    }
                }
        }
    }


    private void removeTaskNotification(Uri uri)
    {
        mNotificationPrefs.edit().remove(uri.toString()).apply();
        NotificationManagerCompat.from(this).cancel("tasks", (int) ContentUris.parseId(uri));
    }
}