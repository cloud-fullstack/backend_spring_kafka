package com.omero.common.transport;

import java.io.Serializable;

public interface MessageTransport<T extends Serializable> {
    void send(String destination, T payload);
    void setMode(TransportMode mode);
    TransportMode getCurrentMode();
}
