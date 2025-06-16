package com.omero.common.transport.impl;

import com.omero.common.transport.MessageTransport;
import com.omero.common.transport.TransportMode;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

@Service
public class KafkaTransport<T extends Serializable> implements MessageTransport<T> {
    private static final Logger log = LoggerFactory.getLogger(KafkaTransport.class);
    
    @Value("${kafka.bootstrap.servers}")
    private String bootstrapServers;
    
    @Autowired
    private MessageTransport<T> fallbackTransport;
    
    private KafkaProducer<String, T> producer;
    private TransportMode currentMode = TransportMode.AUTO;

    @Autowired
    public KafkaTransport() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(props);
    }

    @Override
    public void send(String destination, T payload) {
        try {
            if (currentMode == TransportMode.REST) {
                fallbackTransport.send(destination, payload);
                return;
            }

            ProducerRecord<String, T> record = new ProducerRecord<>(destination, payload);
            RecordMetadata metadata = producer.send(record).get();
            log.info("Successfully sent message to topic {} with offset {}", 
                    metadata.topic(), metadata.offset());
        } catch (Exception e) {
            log.warn("Kafka failed, falling back to REST", e);
            fallbackTransport.send(destination, payload);
        }
    }

    @Override
    public void setMode(TransportMode mode) {
        this.currentMode = mode;
    }

    @Override
    public TransportMode getCurrentMode() {
        return currentMode;
    }
}
