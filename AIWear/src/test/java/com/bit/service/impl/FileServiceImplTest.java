package com.bit.service.impl;

import com.bit.entity.FileInfo;
import com.bit.mapper.FileMapper;
import com.bit.service.PythonService;
import com.bit.util.JwtUtil;
import com.bit.util.OssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileServiceImplTest {

    @TempDir
    Path directory;
    private FileServiceImpl service;
    private PythonService python;
    private OssUtil oss;
    private FileMapper mapper;
    private MockMultipartFile file;

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl();
        JwtUtil jwt = mock(JwtUtil.class);
        python = mock(PythonService.class);
        oss = mock(OssUtil.class);
        mapper = mock(FileMapper.class);
        ReflectionTestUtils.setField(service, "jwtUtil", jwt);
        ReflectionTestUtils.setField(service, "pythonService", python);
        ReflectionTestUtils.setField(service, "ossUtil", oss);
        ReflectionTestUtils.setField(service, "fileMapper", mapper);
        ReflectionTestUtils.setField(service, "uploadDir", directory.toString());
        ReflectionTestUtils.setField(service, "maxSize", 1024L);
        when(jwt.parseToken("Bearer token")).thenReturn("token");
        when(jwt.getUserId("token")).thenReturn(123L);
        file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    void approvesBeforeOssAndIndexing() {
        when(python.validateImage(any(Path.class))).thenAnswer(invocation -> {
            assertArrayEquals(file.getBytes(), Files.readAllBytes(invocation.getArgument(0)));
            return true;
        });
        when(oss.upload(anyString(), any(InputStream.class))).thenReturn("https://oss.test/photo.png");
        assertEquals("https://oss.test/photo.png", service.uploadImage(file, "Bearer token").getUrl());
        var order = inOrder(python, oss, mapper);
        order.verify(python).validateImage(any(Path.class));
        order.verify(oss).upload(anyString(), any(InputStream.class));
        order.verify(mapper).insert(any(FileInfo.class));
        order.verify(python).uploadImage(123L, "https://oss.test/photo.png");
    }

    @Test
    void rejectedImageIsDeletedAndNotUploaded() throws Exception {
        when(python.validateImage(any(Path.class))).thenReturn(false);
        assertThrows(RuntimeException.class, () -> service.uploadImage(file, "Bearer token"));
        assertNoUpload();
    }

    @Test
    void unavailableAuditIsDeletedAndNotUploaded() throws Exception {
        when(python.validateImage(any(Path.class))).thenThrow(new RuntimeException("服务异常"));
        assertThrows(RuntimeException.class, () -> service.uploadImage(file, "Bearer token"));
        assertNoUpload();
    }

    private void assertNoUpload() throws Exception {
        try (var paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        }
        verifyNoInteractions(oss, mapper);
        verify(python, never()).uploadImage(anyLong(), anyString());
    }
}
