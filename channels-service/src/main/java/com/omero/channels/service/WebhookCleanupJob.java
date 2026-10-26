package com.omero.channels.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WebhookCleanupJob {

    private final WhatsAppService whatsapp;

    public WebhookCleanupJob(WhatsAppService whatsapp) {
        this.whatsapp = whatsapp;
    }

    @Scheduled(cron = "0 40 3 * * *")
    public void purge() {
        whatsapp.purgeOldWebhookEvents();
    }
}
