package com.bit.service;

import com.bit.dto.request.EditImageRequest;
import com.bit.dto.request.SearchImageRequest;
import com.bit.dto.response.EditImageResponse;
import com.bit.dto.response.ImageUploadResponse;
import com.bit.dto.response.SearchImageResponse;
import com.bit.entity.FileInfo;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FileService {
    ImageUploadResponse uploadImage(MultipartFile file, String authorization);

    List<FileInfo> myImages(String authorization);

    List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest);

    EditImageResponse edit(String authorization, @Valid EditImageRequest editImageRequest);
}
