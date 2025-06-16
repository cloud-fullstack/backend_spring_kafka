package com.omero.common.transport.health;

import com.omero.common.transport.MessageTransport;
import com.omero.common.transport.TransportMode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaHealthMonitor {
    private static final Logger log = LoggerFactory.getLogger(KafkaHealthMonitor.class);
    
    @Value("${kafka.bootstrap.servers}")
    private String bootstrapServers;
    
    private final MessageTransport<?> messageTransport;

    public KafkaHealthMonitor(MessageTransport<?> messageTransport) {
        this.messageTransport = messageTransport;
    }

    @Scheduled(fixedRate = 30000) // Check every 30 seconds
    public void checkAndToggle() {
        try {
            if (!isKafkaUp()) {
                log.warn("Kafka is down, switching to REST transport");
                messageTransport.setMode(TransportMode.REST);
            }
        } catch (Exception e) {
            log.error("Error checking Kafka health", e);
        }
    }

    private boolean isKafkaUp() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        try (AdminClient adminClient = AdminClient.create(props)) {
            return adminClient.describeCluster().nodes().get().size() > 0;
        } catch (Exception e) {
            log.error("Failed to connect to Kafka", e);
            return false;
        }
    }
}
