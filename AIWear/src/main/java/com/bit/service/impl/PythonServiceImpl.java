package com.bit.service.impl;

import com.bit.dto.request.EditImageRequest;
import com.bit.dto.request.SearchImageRequest;
import com.bit.dto.response.EditImageResponse;
import com.bit.dto.response.PythonImageUploadResponse;
import com.bit.dto.response.SearchImageResponse;
import com.bit.service.PythonService;
import com.bit.util.OssUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PythonServiceImpl implements PythonService {
    private final RestTemplate restTemplate;

    private final String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private OssUtil ossUtil;

    public PythonServiceImpl(@Value("${python.base-url:http://localhost:5000}") String baseUrl,
                             @Value("${python.connect-timeout:5000}") int connectTimeout,
                             @Value("${python.read-timeout:180000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public PythonImageUploadResponse uploadImage(Long userId, String ossUrl) {
        if (userId == null || ossUrl == null || ossUrl.isBlank()) {
            throw new RuntimeException("Python图片索引参数不能为空");
        }
        PythonImageUploadResponse response;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of("userId", userId.toString(), "ossUrl", ossUrl);
            response = restTemplate.postForObject(baseUrl + "/api/upload-image",
                    new HttpEntity<>(body, headers), PythonImageUploadResponse.class);
        } catch (RestClientException e) {
            log.error("调用Python图片索引失败，userId:{}, 异常类型:{}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("图片搜索索引生成失败，请检查Python服务");
        }
        if (response == null || !Integer.valueOf(200).equals(response.getCode())
                || response.getId() == null || response.getId().isBlank()) {
            throw new RuntimeException("Python图片索引服务返回失败");
        }
        return response;
    }

    @Override
    public boolean validateImage(Path filePath) {
        if (filePath == null || !Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
            throw new RuntimeException("待审核图片不存在或不可读取");
        }
        JsonNode result;
        try {
            String url = baseUrl + "/api/validate-image";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(filePath));
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            if (response.getBody() == null) {
                throw new IllegalStateException("审核响应为空");
            }
            result = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("调用Python图片审核失败，异常类型:{}", e.getClass().getSimpleName());
            throw new RuntimeException("图片审核服务异常，请稍后重试");
        }
        if (result == null || !result.path("code").isIntegralNumber()
                || result.path("code").asInt() != 200 || !result.path("allow").isBoolean()) {
            throw new RuntimeException("Python图片审核服务返回异常");
        }
        return result.path("allow").asBoolean();
    }

    @Override
    public List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest) {
        String url = baseUrl + "/api/search-image";
        String query = searchImageRequest.getQuery();
        MultipartFile file = searchImageRequest.getFile();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("userId", userId.toString());
            if(query != null && !query.isBlank()) {
                body.add("query", query);
            }
            if(file != null) {
                byte[] bytes = file.getBytes();
                String fileName = file.getOriginalFilename();
                ByteArrayResource fileResource = new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return fileName;
                    }
                };
                body.add("file", fileResource);
            }
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            if (response.getBody() == null) {
                throw new IllegalStateException("搜索响应为空");
            }
            JsonNode result = objectMapper.readTree(response.getBody());
            if (result == null || !result.path("code").isIntegralNumber()
                    || result.path("code").asInt() != 200 || !result.path("data").isArray()) {
                throw new IllegalStateException("搜索响应格式异常或服务返回失败");
            }
            List<SearchImageResponse> searchImageResponseList = new ArrayList<>();
            for(JsonNode node : result.path("data")) {
                if (!node.path("filePath").isTextual() || node.path("filePath").asText().isBlank()) {
                    throw new IllegalStateException("搜索结果缺少图片地址");
                }
                SearchImageResponse searchImageResponse = new SearchImageResponse();
                searchImageResponse.setFilePath(node.path("filePath").asText(""));
                searchImageResponseList.add(searchImageResponse);
            }
            return searchImageResponseList;
        } catch (Exception e) {
            log.error("调用Python图片搜索失败，userId:{}, 异常类型:{}", userId, e.getClass().getSimpleName());
            throw new RuntimeException("图片搜索失败，请检查参数或稍后重试");
        }
    }

    @Override
    public EditImageResponse edit(EditImageRequest editImageRequest) {
        String sourceImageUrl = editImageRequest.getImage();
        String instruction = editImageRequest.getInstruction();
        Path sourceTemp = null;
        Path editTemp = null;
        try {
            sourceTemp = downloadToTempFile(sourceImageUrl);
            String url = baseUrl + "/api/skill/image";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(sourceTemp.toFile()));
            body.add("instruction", instruction);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, requestEntity, String.class);
            if (response.getBody() == null) {
                throw new RuntimeException("图片编辑服务返回为空");
            }
            JsonNode result = objectMapper.readTree(response.getBody());
            if (result == null || !result.path("success").isBoolean()
                    || !result.path("success").asBoolean() || !result.path("url").isTextual()) {
                throw new RuntimeException("图片编辑服务返回失败或格式异常");
            }
            String pythonUrl = result.path("url").asText("");
            URI imageUri = URI.create(pythonUrl);
            if ((!"http".equalsIgnoreCase(imageUri.getScheme())
                    && !"https".equalsIgnoreCase(imageUri.getScheme())) || imageUri.getHost() == null) {
                throw new RuntimeException("图片编辑服务未返回有效图片地址");
            }
            editTemp = downloadToTempFile(pythonUrl);
            String extension = ".png";
            String objectKey = "image/edited" + UUID.randomUUID().toString().replace("-", "") + extension;
            String saveUrl = ossUtil.upload(objectKey, Files.newInputStream(editTemp));
            EditImageResponse editImageResponse = new EditImageResponse();
            editImageResponse.setUrl(pythonUrl);
            editImageResponse.setSaveUrl(saveUrl);
            return editImageResponse;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            for (Path temp : new Path[]{sourceTemp, editTemp}) {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException | SecurityException e) {
                        log.warn("清理编辑图片临时文件失败，异常类型:{}", e.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    private Path downloadToTempFile(String fileUrl) {
        try {
            URL url = URI.create(fileUrl).toURL();
            Path tempFile = Files.createTempFile("image-edit-", ".tmp");
            InputStream inputStream = url.openStream();
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
