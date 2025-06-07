package com.omero.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.springframework.stereotype.Service;

@Service
public class PushNotificationService {

    public void sendPushNotification(String deviceId, String message) {
        Message notification = Message.builder()
                .setToken(deviceId)
                .setNotification(Notification.builder()
                        .setTitle("Omero Notification")
                        .setBody(message)
                        .build())
                .build();

        try {
            FirebaseMessaging.getInstance().send(notification);
        } catch (FirebaseMessagingException e) {
            throw new RuntimeException("Failed to send push notification", e);
        }
    }

    public void sendPushNotificationWithCustomData(String deviceId, String message, String title, Map<String, String> data) {
        Message notification = Message.builder()
                .setToken(deviceId)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(message)
                        .build())
                .putAllData(data)
                .build();

        try {
            FirebaseMessaging.getInstance().send(notification);
        } catch (FirebaseMessagingException e) {
            throw new RuntimeException("Failed to send push notification", e);
        }
    }
}
