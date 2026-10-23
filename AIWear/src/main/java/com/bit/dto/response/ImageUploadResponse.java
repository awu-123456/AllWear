package com.bit.dto.response;

import lombok.Data;

import java.io.Serializable;

@Data
public class ImageUploadResponse implements Serializable {

    private String url;

    private String fileName;

    private Long fileSize;
}
