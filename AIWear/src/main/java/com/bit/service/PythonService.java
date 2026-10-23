package com.bit.service;

import com.bit.dto.response.PythonImageUploadResponse;

import java.nio.file.Path;

public interface PythonService {

    PythonImageUploadResponse uploadImage(Long userId, String ossUrl);

    boolean validateImage(Path filePath);
}
