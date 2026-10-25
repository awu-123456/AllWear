package com.bit.service.impl;

import com.bit.dto.request.EditImageRequest;
import com.bit.util.OssUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PythonServiceEditTest {

    private PythonServiceImpl service;
    private OssUtil oss;
    private MockRestServiceServer server;
    private HttpServer downloads;
    private String downloadUrl;
    private Set<Path> originalFiles;

    @BeforeEach
    void setUp() throws Exception {
        service = new PythonServiceImpl("http://localhost:5000", 5000, 180000);
        oss = mock(OssUtil.class);
        ReflectionTestUtils.setField(service, "ossUtil", oss);
        server = MockRestServiceServer.createServer(
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate"));
        originalFiles = editTempFiles();
        downloads = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downloads.createContext("/image", exchange -> {
            byte[] bytes = "image-content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
            exchange.close();
        });
        downloads.start();
        downloadUrl = "http://127.0.0.1:" + downloads.getAddress().getPort() + "/image";
    }

    @AfterEach
    void stopDownloads() {
        if (downloads != null) {
            downloads.stop(0);
        }
    }

    private Set<Path> editTempFiles() throws Exception {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("image-edit-"))
                    .collect(Collectors.toSet());
        }
    }

    private EditImageRequest request() {
        EditImageRequest request = new EditImageRequest();
        request.setImage(downloadUrl);
        request.setInstruction("修改背景");
        return request;
    }

    private void assertTempFilesCleaned() throws Exception {
        Set<Path> remaining = editTempFiles();
        remaining.removeAll(originalFiles);
        assertTrue(remaining.isEmpty(), "Unexpected edit temp files: " + remaining);
    }

    @Test
    void editsThroughCorrectRouteAndUploadsResult() throws Exception {
        server.expect(requestTo("http://localhost:5000/api/skill/image"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"instruction\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"file\"")))
                .andRespond(withSuccess("{\"success\":true,\"url\":\"" + downloadUrl + "\"}", MediaType.APPLICATION_JSON));
        when(oss.upload(anyString(), any(InputStream.class))).thenAnswer(invocation -> {
            assertTrue(invocation.<String>getArgument(0).endsWith(".png"));
            // 模拟 OSS 消费输入流；生产上传语句保持不变。
            try (InputStream input = invocation.getArgument(1)) {
                assertEquals("image-content", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            return "https://oss.test/edited.png";
        });
        var result = service.edit(request());
        assertEquals(downloadUrl, result.getUrl());
        assertEquals("https://oss.test/edited.png", result.getSaveUrl());
        server.verify();
        assertTempFilesCleaned();
    }

    @Test
    void httpFailureStillCleansSourceAndPreservesError() throws Exception {
        server.expect(requestTo("http://localhost:5000/api/skill/image"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.edit(request()));
        assertInstanceOf(org.springframework.web.client.HttpServerErrorException.class, error.getCause());
        verifyNoInteractions(oss);
        server.verify();
        assertTempFilesCleaned();
    }

    @Test
    void rejectsInvalidResponsesWithoutNullPointerOrUpload() throws Exception {
        String[] responses = {"", "null", "bad json", "{\"success\":false}",
                "{\"url\":\"" + downloadUrl + "\"}", "{\"success\":true}",
                "{\"success\":true,\"url\":\"\"}", "{\"success\":true,\"url\":\"file:///a.png\"}",
                "{\"success\":\"true\",\"url\":\"" + downloadUrl + "\"}",
                "{\"success\":true,\"url\":123}"};
        for (String response : responses) {
            server.expect(requestTo("http://localhost:5000/api/skill/image"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        for (String response : responses) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.edit(request()), response);
            assertFalse(error instanceof NullPointerException);
            assertTempFilesCleaned();
        }
        verifyNoInteractions(oss);
        server.verify();
    }

    @Test
    void invalidSourceDoesNotTriggerNullCleanup() throws Exception {
        EditImageRequest request = request();
        request.setImage("not a URL");
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.edit(request));
        assertFalse(error instanceof NullPointerException);
        verifyNoInteractions(oss);
        server.verify();
        assertTempFilesCleaned();
    }
}
