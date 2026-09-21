package com.example.quickdrop;

import com.example.quickdrop.controller.HomeController;
import com.example.quickdrop.controller.OwnerAccessService;
import com.example.quickdrop.controller.OwnerPinService;
import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.qr.QrCodeService;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class SelectedDownloadTests {

    @TempDir
    Path temporaryDirectory;

    private Path uploads;
    private ShareLinkService shareLinkService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        uploads = Files.createDirectory(temporaryDirectory.resolve("uploads"));
        shareLinkService = mock(ShareLinkService.class);
        HomeController controller = new HomeController(
                shareLinkService,
                mock(ReceiveLinkService.class),
                mock(FileEventService.class),
                mock(NetworkAddressService.class),
                mock(QrCodeService.class),
                mock(OwnerAccessService.class),
                mock(OwnerPinService.class),
                8080
        );
        ReflectionTestUtils.setField(controller, "uploadDirectory", uploads);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void oneSelectedFileKeepsItsNameAndExactBytes() throws Exception {
        byte[] contents = new byte[]{0, 1, 2, 127, -128, -1};
        String filename = "résumé 2026.bin";
        addFile("first", filename, contents);

        MockHttpServletResponse response = download("first");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).containsExactly(contents);
        assertThat(ContentDisposition.parse(response.getHeader(
                HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo(filename);
        assertThat(response.getContentLengthLong()).isEqualTo(contents.length);
        assertThat(response.getContentType()).isNotEqualTo("application/zip");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        verify(shareLinkService, never()).createBundle(anyList());
    }

    @Test
    void multipleSelectedFilesProduceOneZipWithExactContents() throws Exception {
        byte[] first = new byte[]{1, 2, 3, -1};
        byte[] second = new byte[]{0, -128, 127, 4};
        addFile("first", "first.bin", first);
        addFile("second", "résumé.bin", second);

        MockHttpServletResponse response = download("first", "second");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(ContentDisposition.parse(response.getHeader(
                HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("quick-drop-files.zip");
        Map<String, byte[]> entries = readZip(response.getContentAsByteArray());
        assertThat(entries.keySet()).containsExactly("first.bin", "résumé.bin");
        assertThat(entries.get("first.bin")).containsExactly(first);
        assertThat(entries.get("résumé.bin")).containsExactly(second);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        verify(shareLinkService, never()).createBundle(anyList());
    }

    @Test
    void repeatedTokensStillDownloadOneOriginalFile() throws Exception {
        byte[] contents = new byte[]{10, 20, 30};
        addFile("first", "single.bin", contents);

        MockHttpServletResponse response = download("first", "first");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).containsExactly(contents);
        assertThat(ContentDisposition.parse(response.getHeader(
                HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("single.bin");
        verify(shareLinkService, times(1)).findFilename("first");
    }

    @Test
    void missingSelectionIsRejected() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/download/selected")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andReturn().getResponse();

        assertRejected(response, 400);
    }

    @Test
    void blankTokenIsRejected() throws Exception {
        assertRejected(download(" "), 400);
    }

    @Test
    void staleTokenRejectsTheWholeSelectionBeforeAnAttachmentStarts()
            throws Exception {
        addFile("first", "available.bin", new byte[]{1, 2, 3});
        when(shareLinkService.findFilename("expired"))
                .thenReturn(Optional.empty());

        assertRejected(download("first", "expired"), 409);
    }

    @Test
    void fileRemovedFromDiskRejectsTheWholeSelection() throws Exception {
        addFile("first", "available.bin", new byte[]{1, 2, 3});
        when(shareLinkService.findFilename("missing"))
                .thenReturn(Optional.of("missing.bin"));

        assertRejected(download("first", "missing"), 409);
    }

    @Test
    void resolvedFilenameCannotEscapeTheUploadDirectory() throws Exception {
        Files.write(temporaryDirectory.resolve("outside.bin"), new byte[]{9, 8});
        when(shareLinkService.findFilename("outside"))
                .thenReturn(Optional.of("../outside.bin"));

        assertRejected(download("outside"), 409);
    }

    private void addFile(String token, String filename, byte[] contents)
            throws Exception {
        Files.write(uploads.resolve(filename), contents);
        when(shareLinkService.findFilename(token))
                .thenReturn(Optional.of(filename));
    }

    private MockHttpServletResponse download(String... tokens) throws Exception {
        return mvc.perform(post("/download/selected")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("tokens", tokens))
                .andReturn().getResponse();
    }

    private void assertRejected(MockHttpServletResponse response, int status)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).isNotBlank();
    }

    private Map<String, byte[]> readZip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                assertThat(entries).doesNotContainKey(entry.getName());
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
