package com.bit.dto.response;

import lombok.Data;

import java.io.Serializable;

@Data
public class AuthResponse implements Serializable {

    private Long userId;

    private String userName;

    private String email;

    private String token;
}
