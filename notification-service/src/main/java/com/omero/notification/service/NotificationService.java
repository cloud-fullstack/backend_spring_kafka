package com.omero.notification.service;

import com.omero.notification.model.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

@Service
public class NotificationService {

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private PushNotificationService pushService;

    @Autowired
    private InAppNotificationService inAppService;

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    public void sendNotification(String recipient, String message, NotificationType type) {
        switch (type) {
            case EMAIL:
                sendEmailNotification(recipient, message);
                break;
            case SMS:
                sendSmsNotification(recipient, message);
                break;
            case PUSH:
                sendPushNotification(recipient, message);
                break;
            case IN_APP:
                sendInAppNotification(recipient, message);
                break;
        }
    }

    private void sendEmailNotification(String email, String message) {
        SimpleMailMessage mailMessage = new SimpleMailMessage();
        mailMessage.setTo(email);
        mailMessage.setSubject("Omero Notification");
        mailMessage.setText(message);
        mailSender.send(mailMessage);
    }

    private void sendSmsNotification(String phoneNumber, String message) {
        Twilio.init(accountSid, authToken);
        Message.creator(
                new PhoneNumber(phoneNumber),
                new PhoneNumber(twilioPhoneNumber),
                message
        ).create();
    }

    private void sendPushNotification(String deviceId, String message) {
        pushService.sendPushNotification(deviceId, message);
    }

    private void sendInAppNotification(String userId, String message) {
        inAppService.sendInAppNotification(userId, message);
    }
}
