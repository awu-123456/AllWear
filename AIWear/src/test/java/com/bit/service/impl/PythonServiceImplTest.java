package com.bit.service.impl;

import com.bit.dto.request.SearchImageRequest;
import org.springframework.mock.web.MockMultipartFile;
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
    @Test
    void searchSendsUserIdAndReadsDataArrayInOrder() {
        server.expect(requestTo("http://localhost:5000/api/search-image"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"userId\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("123")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"query\"")))
                .andRespond(withSuccess("{\"code\":200,\"message\":\"ok\",\"data\":["
                        + "{\"filePath\":\"https://oss.test/a.png\",\"similarity\":0.9},"
                        + "{\"filePath\":\"https://oss.test/b.png\",\"similarity\":0.8}]}", MediaType.APPLICATION_JSON));
        SearchImageRequest request = new SearchImageRequest();
        request.setQuery("上衣");
        var results = service.search(123L, request);
        assertEquals(2, results.size());
        assertEquals("https://oss.test/a.png", results.get(0).getFilePath());
        assertEquals("https://oss.test/b.png", results.get(1).getFilePath());
        server.verify();
    }

    @Test
    void searchSendsActualImageFile() {
        server.expect(requestTo("http://localhost:5000/api/search-image"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"userId\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"file\"; filename=\"photo.png\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("image-content")))
                .andRespond(withSuccess("{\"code\":200,\"data\":[]}", MediaType.APPLICATION_JSON));
        SearchImageRequest request = new SearchImageRequest();
        request.setFile(new MockMultipartFile("file", "photo.png", "image/png", "image-content".getBytes()));
        assertTrue(service.search(123L, request).isEmpty());
        server.verify();
    }

    @Test
    void searchDistinguishesEmptySuccessFromInvalidResponses() {
        String[] responses = {"{\"code\":200,\"data\":[]}", "{\"code\":500,\"data\":[]}",
                "{\"code\":200}", "{\"code\":200,\"data\":{}}", "null", "not json", "",
                "{\"code\":200,\"data\":[{}]}", "{\"data\":[]}"};
        for (String response : responses) {
            server.expect(requestTo("http://localhost:5000/api/search-image"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        SearchImageRequest request = new SearchImageRequest();
        request.setQuery("上衣");
        assertTrue(service.search(123L, request).isEmpty());
        for (int index = 1; index < responses.length; index++) {
            assertThrows(RuntimeException.class, () -> service.search(123L, request));
        }
        server.verify();
    }

    @Test
    void searchHttpErrorsAreNotEmptyResults() {
        server.expect(requestTo("http://localhost:5000/api/search-image"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo("http://localhost:5000/api/search-image"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        SearchImageRequest request = new SearchImageRequest();
        request.setQuery("上衣");
        assertThrows(RuntimeException.class, () -> service.search(123L, request));
        assertThrows(RuntimeException.class, () -> service.search(123L, request));
        server.verify();
    }

    @Test
    void searchTimeoutIsNotEmptyResult() {
        server.expect(requestTo("http://localhost:5000/api/search-image"))
                .andRespond(withException(new java.net.SocketTimeoutException("SECRET endpoint")));
        SearchImageRequest request = new SearchImageRequest();
        request.setQuery("上衣");
        RuntimeException error = assertThrows(RuntimeException.class, () -> service.search(123L, request));
        assertFalse(error.getMessage().contains("SECRET"));
        server.verify();
    }
}
