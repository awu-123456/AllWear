package com.bit.dto.response;

import lombok.Data;

@Data
public class PythonImageUploadResponse {

    private Integer code;

    private String description;

    private String id;

    private String ossUrl;

    private String userId;

    private Integer vectorDim;

    private String error;
}
