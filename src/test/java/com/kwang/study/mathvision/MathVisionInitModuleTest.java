package com.kwang.study.mathvision;

import com.kwang.study.fs.exception.PathAlreadyExistsException;
import com.kwang.study.fs.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MathVisionInitModuleTest {

    @Test
    void createsMathVisionStorageRootAtStartup() throws Exception {
        FileStorageService fileStorageService = mock(FileStorageService.class);

        new MathVisionInitModule(fileStorageService).run(null);

        verify(fileStorageService).createDirectory("/mathvision");
    }

    @Test
    void acceptsExistingMathVisionStorageRoot() throws Exception {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        doThrow(new PathAlreadyExistsException("/mathvision"))
                .when(fileStorageService).createDirectory("/mathvision");

        assertDoesNotThrow(() -> new MathVisionInitModule(fileStorageService).run(null));
    }

    @Test
    void exposesUnexpectedStorageInitializationFailure() throws Exception {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        doThrow(new IOException("storage unavailable"))
                .when(fileStorageService).createDirectory("/mathvision");

        assertThrows(IOException.class, () -> new MathVisionInitModule(fileStorageService).run(null));
    }
}
