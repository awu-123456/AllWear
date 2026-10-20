package com.bit.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder()
public class SendVerificationCodeResponse {

    private String sendTo;
    private Integer expireTime;

}
