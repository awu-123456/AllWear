package com.bit.service;

import com.bit.dto.response.ImageUploadResponse;
import com.bit.entity.FileInfo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FileService {
    ImageUploadResponse uploadImage(MultipartFile file, String authorization);

    List<FileInfo> myImages(String authorization);
}
