package com.bit.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class SearchImageRequest {

    private String query;
    private MultipartFile file;
}
