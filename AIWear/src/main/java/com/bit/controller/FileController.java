package com.bit.controller;

import com.bit.common.Result;
import com.bit.dto.response.ImageUploadResponse;
import com.bit.entity.FileInfo;
import com.bit.log.ApiLog;
import com.bit.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/file")
public class FileController {

    @Autowired
    private FileService fileService;

    @ApiLog
    @PostMapping("/upload/image")
    public Result<ImageUploadResponse> uploadImage(@RequestHeader(value = "Authorization") String authorization,
                                                   @RequestParam("file") MultipartFile file) {
        return Result.success("图片上传成功", fileService.uploadImage(file, authorization));
    }

    @ApiLog
    @GetMapping("/my-images")
    public Result<List<FileInfo>> myImages(@RequestHeader(value = "Authorization") String authorization) {
        List<FileInfo> fileInfos = fileService.myImages(authorization);
        return Result.success("查询成功", fileInfos);
    }
}
