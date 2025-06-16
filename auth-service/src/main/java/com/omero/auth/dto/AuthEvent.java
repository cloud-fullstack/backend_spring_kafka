package com.omero.auth.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuthEvent {
    private String userId;
    private String action; // login, logout, register
    
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
    
    private String jwtClaims;
    private String planName;
    private boolean isReseller;
}
