package com.mihir.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the launcher see what is playing: MediaSessionManager.getActiveSessions
 * needs an enabled notification listener. It reads no notifications. Allowed over ADB at install
 * (cmd notification allow_listener).
 */
public class MediaListener extends NotificationListenerService { }
