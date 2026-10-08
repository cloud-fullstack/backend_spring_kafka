package com.omero.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SocialUser {
    private String iss;
    private String sub;
    private String aud;
    private String email;
    private boolean emailVerified;
    private String name;
    private String picture;
    private String givenName;
    private String familyName;
    private String locale;
}
