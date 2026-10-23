package com.bit.service.impl;

import com.bit.dto.response.PythonImageUploadResponse;
import com.bit.service.PythonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.FileSystemResource;
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

import java.nio.file.Path;
import java.util.Map;

@Slf4j
@Service
public class PythonServiceImpl implements PythonService {

    private final RestTemplate restTemplate;

    private final String baseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
}
