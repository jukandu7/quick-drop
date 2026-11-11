package com.example.quickdrop;

import com.example.quickdrop.controller.*;
import com.example.quickdrop.share.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:share-revocation")
class ShareRevocationTests {
    @Autowired HomeController home;
    @Autowired UploadController uploadsController;
    @Autowired DownloadController downloads;
    @Autowired FileManagementController management;
    @Autowired ShareLinkService shares;
    @MockitoSpyBean SharedFileRepository files;
    @Autowired ShareBundleRepository bundles;
    @Autowired OwnerAccessFilter access;
    @Autowired CsrfProtectionFilter csrf;
    @MockitoBean OwnerPinService pins;
    @TempDir Path uploads;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        bundles.deleteAll();
        files.deleteAll();
        ReflectionTestUtils.setField(shares, "uploadDirectory", uploads);
        when(pins.isConfigured()).thenReturn(true);
        when(pins.authenticationVersion()).thenReturn("test");
        mvc = MockMvcBuilders.standaloneSetup(home, uploadsController, downloads, management).addFilters(access, csrf).build();
    }

    private TransferResponses.UploadResult upload(String name, String contents, boolean overwrite) throws Exception {
        var response = uploadsController.upload(new MockMultipartFile("file", name, "text/plain", contents.getBytes()), "24h", overwrite);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    @Test
    void overwriteRevokesSingleAndBundleLinksAndNeverReusesPhysicalPath() throws Exception {
        var first = upload("report.txt", "old", false);
        var second = upload("other.txt", "other", false);
        var bundle = shares.createBundle(List.of(first.shareToken(), second.shareToken())).orElseThrow();
        var oldFile = shares.findFile(first.shareToken()).orElseThrow();
        var pendingDownload = downloads.downloadSharedFile(first.shareToken());
        var pendingBundle = downloads.downloadShareBundle(bundle.token());

        var replacement = upload("report.txt", "private replacement", true);
        assertThat(replacement.shareToken()).isNotEqualTo(first.shareToken());
        assertThat(shares.findFile(replacement.shareToken()).orElseThrow().path()).isNotEqualTo(oldFile.path());
        mvc.perform(get("/share/" + first.shareToken())).andExpect(status().isNotFound());
        mvc.perform(get("/share/bundle/" + bundle.token())).andExpect(status().isNotFound());
        mvc.perform(get("/share/" + replacement.shareToken())).andExpect(status().isOk())
                .andExpect(content().string("private replacement"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(pendingDownload.getBody().exists()).isFalse();
        var bytes = new ByteArrayOutputStream();
        pendingBundle.getBody().writeTo(bytes);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("other.txt");
            assertThat(new String(zip.readAllBytes())).isEqualTo("other");
            assertThat(zip.getNextEntry()).isNull();
        }
        assertThat(files.count()).isEqualTo(2);
    }

    @Test
    void revocationKeepsBytesAndExpirationButInvalidatesAllRelatedBundles() throws Exception {
        var first = upload("keep.txt", "keep these bytes", false);
        var second = upload("other.txt", "other", false);
        var bundle = shares.createBundle(List.of(first.shareToken(), second.shareToken())).orElseThrow();
        var before = files.findById(first.shareToken()).orElseThrow();
        Path originalPath = shares.findFile(first.shareToken()).orElseThrow().path();
        assertThat(shares.revokeLinks(List.of(first.shareToken(), first.shareToken(), "missing")))
                .containsExactly(first.shareToken());
        var after = files.findByFilename("keep.txt").orElseThrow();
        assertThat(after.getToken()).isNotEqualTo(first.shareToken());
        assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
        assertThat(shares.findFile(after.getToken()).orElseThrow().path()).isEqualTo(originalPath);
        assertThat(Files.readString(originalPath)).isEqualTo("keep these bytes");
        mvc.perform(get("/share/" + first.shareToken())).andExpect(status().isNotFound());
        mvc.perform(get("/share/bundle/" + bundle.token())).andExpect(status().isNotFound());
        mvc.perform(get("/share/" + after.getToken())).andExpect(content().string("keep these bytes"));
        assertThat(shares.findFile(second.shareToken())).isPresent();
        assertThat(home.filesSnapshot().getBody().files()).hasSize(2);
    }

    @Test
    void legacyFilesRemainReadableAndUpgradeOnReplacement() throws Exception {
        Files.writeString(uploads.resolve("legacy.txt"), "legacy bytes");
        var legacy = files.saveAndFlush(new SharedFile("legacy-token", "legacy.txt", null));
        mvc.perform(get("/share/" + legacy.getToken())).andExpect(content().string("legacy bytes"));
        var updated = upload("legacy.txt", "new bytes", true);
        assertThat(Files.exists(uploads.resolve("legacy.txt"))).isFalse();
        assertThat(files.findById(updated.shareToken()).orElseThrow().getStorageKey()).isNotNull();
        mvc.perform(get("/share/" + legacy.getToken())).andExpect(status().isNotFound());
        mvc.perform(get("/download").param("filename", "legacy.txt")).andExpect(content().string("new bytes"));
    }

    @Test
    void duplicatesStillRequireOverwriteAndDeletionAndExpirationRemoveVersionedFiles() throws Exception {
        var first = upload("report.txt", "original", false);
        Path path = shares.findFile(first.shareToken()).orElseThrow().path();
        assertThat(uploadsController.upload(new MockMultipartFile("file", "report.txt", "text/plain", "other".getBytes()),
                "24h", false).getStatusCode().value()).isEqualTo(409);
        assertThat(Files.readString(path)).isEqualTo("original");
        var row = files.findById(first.shareToken()).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        files.saveAndFlush(row);
        shares.deleteExpiredFiles();
        assertThat(Files.exists(path)).isFalse();
        var second = upload("delete.txt", "delete", false);
        Path secondPath = shares.findFile(second.shareToken()).orElseThrow().path();
        shares.deleteByTokens(List.of(second.shareToken()));
        assertThat(Files.exists(secondPath)).isFalse();
        assertThat(home.filesSnapshot().getBody().files()).isEmpty();
    }

    @Test
    void revocationRequiresOwnerAccessAndCsrfAndPreservesFilesThroughHttp() throws Exception {
        var file = upload("keep.txt", "keep", false);
        String json = "{\"tokens\":[\"" + file.shareToken() + "\"]}";
        mvc.perform(delete("/share-links").contentType("application/json").content(json)
                        .with(request -> { request.setRemoteAddr("192.0.2.5"); return request; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/share-links").contentType("application/json").content(json))
                .andExpect(status().isForbidden());
        var page = mvc.perform(get("/")).andReturn();
        var session = (MockHttpSession) page.getRequest().getSession();
        String token = (String) page.getRequest().getAttribute("csrfToken");
        mvc.perform(delete("/share-links").session(session).header("X-CSRF-Token", token)
                        .contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revokedTokens[0]").value(file.shareToken()));
        assertThat(files.findByFilename("keep.txt")).isPresent();
        mvc.perform(get("/download").param("filename", "keep.txt")).andExpect(content().string("keep"));
    }

    @Test
    void failedMetadataSavePreservesOriginalVersionAndRemovesUnpublishedUpload() throws Exception {
        var original = upload("report.txt", "original", false);
        Path originalPath = shares.findFile(original.shareToken()).orElseThrow().path();
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("test failure"))
                .when(files).saveAndFlush(org.mockito.ArgumentMatchers.any(SharedFile.class));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload("report.txt", "replacement", true))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(Files.readString(originalPath)).isEqualTo("original");
        assertThat(shares.findFile(original.shareToken())).isPresent();
        try (var paths = Files.list(uploads.resolve(".versions"))) {
            assertThat(paths.toList()).containsExactly(originalPath);
        }
    }
}
