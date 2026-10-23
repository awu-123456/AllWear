package com.bit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bit.dto.response.ImageUploadResponse;
import com.bit.entity.FileInfo;
import com.bit.mapper.FileMapper;
import com.bit.service.FileService;
import com.bit.service.PythonService;
import com.bit.util.JwtUtil;
import com.bit.util.OssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private OssUtil ossUtil;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private PythonService pythonService;

    @Value("${aliyun.oss.max-size}")
    private Long maxSize;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImageUploadResponse uploadImage(MultipartFile file, String authorization) {
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);

        validateFile(file);

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "image.png";
        }
        originalFilename = Paths.get(originalFilename).getFileName().toString();

        String extension = getExtension(originalFilename);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String objectKey = storedName;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败");
        }

        Path localPath = Paths.get(uploadDir, storedName);
        try {
            Files.createDirectories(localPath.getParent());
            Files.write(localPath, bytes);
            log.info("图片已保存到系统，path:{}", localPath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("图片保存到系统失败");
        }

        try {
            if (!pythonService.validateImage(localPath)) {
                throw new RuntimeException("图片审核未通过，只允许人物、宠物或服饰类图片");
            }
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(localPath);
            } catch (IOException cleanupException) {
                log.error("审核失败后清理本地图片失败", cleanupException);
            }
            throw e;
        }

        String ossUrl;
        try {
            ossUrl = ossUtil.upload(objectKey, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            try {
                Files.deleteIfExists(localPath);
            } catch (IOException ignored) {
            }
            log.error("图片同步OSS失败，fileName:{}", originalFilename, e);
            throw new RuntimeException("图片同步OSS失败");
        }

        FileInfo fileInfo = new FileInfo();
        fileInfo.setUserId(userId);
        fileInfo.setFileName(originalFilename);
        fileInfo.setFileSize(file.getSize());
        fileInfo.setOssUrl(ossUrl);
        fileMapper.insert(fileInfo);

        pythonService.uploadImage(userId, ossUrl);

        ImageUploadResponse response = new ImageUploadResponse();
        response.setUrl(ossUrl);
        response.setFileName(originalFilename);
        response.setFileSize(file.getSize());
        return response;
    }

    @Override
    public List<FileInfo> myImages(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        Long userId = jwtUtil.getUserId(token);
        LambdaQueryWrapper<FileInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FileInfo::getUserId, userId).orderByDesc(FileInfo::getFileSize);
        return fileMapper.selectList(queryWrapper);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("图片文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new RuntimeException("图片大小不能超过" + maxSize + "字节");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new RuntimeException("只能上传图片文件");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.isBlank()) {
            String extension = getExtension(originalFilename);
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                throw new RuntimeException("不支持的图片格式");
            }
        }
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            throw new RuntimeException("图片文件缺少后缀名");
        }
        return filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
