package com.bit.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Slf4j
@Component
public class OssUtil {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        String endpointUrl = endpoint;
        if (!endpointUrl.startsWith("http://") && !endpointUrl.startsWith("https://")) {
            endpointUrl = "https://" + endpointUrl;
        }
        this.ossClient = new OSSClientBuilder().build(endpointUrl, accessKeyId, accessKeySecret);
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    public String upload(String objectKey, InputStream inputStream) {
        ossClient.putObject(bucketName, objectKey, inputStream);
        String host = endpoint.replace("https://", "").replace("http://", "");
        String url = "https://" + bucketName + "." + host + "/" + objectKey;
        log.info("图片已同步到OSS，url:{}", url);
        return url;
    }
}
