package com.bit.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PythonServiceImplTest {

    private PythonServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        service = new PythonServiceImpl("http://localhost:5000/", 5000, 180000);
        RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        server = MockRestServiceServer.createServer(template);
    }

    @Test
    void sendsUserIdAndOssUrlAsJson() {
        server.expect(requestTo("http://localhost:5000/api/upload-image"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"userId\":\"123\",\"ossUrl\":\"https://oss.test/a.png\"}"))
                .andRespond(withSuccess("{\"code\":200,\"id\":\"index123\",\"vectorDim\":512}", MediaType.APPLICATION_JSON));
        assertEquals("index123", service.uploadImage(123L, "https://oss.test/a.png").getId());
        server.verify();
    }

    @Test
    void rejectsBusinessFailureAndDoesNotRetry() {
        server.expect(requestTo("http://localhost:5000/api/upload-image"))
                .andRespond(withSuccess("{\"code\":500,\"error\":\"failed\"}", MediaType.APPLICATION_JSON));
        assertThrows(RuntimeException.class, () -> service.uploadImage(123L, "https://oss.test/a.png"));
        server.verify();
    }

    @Test
    void rejectsHttpFailureAndDoesNotRetry() {
        server.expect(requestTo("http://localhost:5000/api/upload-image"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThrows(RuntimeException.class, () -> service.uploadImage(123L, "https://oss.test/a.png"));
        server.verify();
    }

    @Test
    void rejectsEmptyResponse() {
        server.expect(requestTo("http://localhost:5000/api/upload-image"))
                .andRespond(withSuccess());
        assertThrows(RuntimeException.class, () -> service.uploadImage(123L, "https://oss.test/a.png"));
        server.verify();
    }
    @Test
    void sendsActualFileForValidation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.writeString(dir.resolve("photo.png"), "image-content");
        server.expect(requestTo("http://localhost:5000/api/validate-image"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"file\"; filename=\"photo.png\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image-content")))
                .andRespond(withSuccess("{\"code\":200,\"allow\":true}", MediaType.APPLICATION_JSON));
        assertTrue(service.validateImage(file));
        server.verify();
    }

    @Test
    void distinguishesRejectionFromInvalidResponse(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path file = java.nio.file.Files.writeString(dir.resolve("photo.png"), "image-content");
        server.expect(requestTo("http://localhost:5000/api/validate-image"))
                .andRespond(withSuccess("{\"code\":200,\"allow\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/api/validate-image"))
                .andRespond(withSuccess("{\"code\":500,\"allow\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/api/validate-image"))
                .andRespond(withSuccess("{\"code\":200}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:5000/api/validate-image"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertFalse(service.validateImage(file));
        assertThrows(RuntimeException.class, () -> service.validateImage(file));
        assertThrows(RuntimeException.class, () -> service.validateImage(file));
        assertThrows(RuntimeException.class, () -> service.validateImage(file));
        server.verify();
    }
}
