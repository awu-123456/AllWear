package com.bit.service;

import com.bit.dto.request.EditImageRequest;
import com.bit.dto.request.SearchImageRequest;
import com.bit.dto.response.EditImageResponse;
import com.bit.dto.response.PythonImageUploadResponse;
import com.bit.dto.response.SearchImageResponse;

import java.nio.file.Path;
import java.util.List;

public interface PythonService {

    PythonImageUploadResponse uploadImage(Long userId, String ossUrl);

    boolean validateImage(Path filePath);

    List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest);

    EditImageResponse edit(EditImageRequest editImageRequest);
}
