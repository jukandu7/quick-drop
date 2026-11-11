package com.example.quickdrop;

import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.controller.OwnerPinService;
import com.example.quickdrop.presentation.DisplayFormats;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.*;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.WebApplicationContext;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:transfer-limits",
        "spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=2KB",
        "quickdrop.storage-quota=2KB", "quickdrop.upload.temp-budget=4KB",
        "quickdrop.upload.minimum-free-space=0B"})
class TransferLimitsTests {
    @Autowired TransferLimits limits;
    @Autowired MultipartConfigElement multipart;
    @Autowired ShareLinkService storage;
    @Autowired ReceiveLinkService receiving;
    @Autowired WebApplicationContext context;
    @MockitoBean OwnerPinService pins;

    @Test void configuredLimitsReachServletAndBothBrowserPages() throws Exception {
        assertThat(multipart.getMaxFileSize()).isEqualTo(1024);
        assertThat(multipart.getMaxRequestSize()).isEqualTo(2048);
        when(pins.isConfigured()).thenReturn(true);
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String token = receiving.start(Duration.ofMinutes(5)).token();
        for (String path : new String[]{"/", "/receive/" + token}) {
            var response = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("id=\"upload-limit\" content=\"1024\" data-label=\"1 KB\"")
                    .doesNotContain("3 GB maximum");
        }
    }

    @Test void storageEnforcesConfiguredFileSizeAndCombinedQuota() throws Exception {
        assertThatThrownBy(() -> upload(1025)).isInstanceOfSatisfying(ShareLinkService.UploadRejected.class,
                error -> { assertThat(error.status()).isEqualTo(413); assertThat(error.getMessage()).contains("1 KB"); });
        upload(1024);
        upload(1024);
        assertThatThrownBy(() -> upload(1)).isInstanceOfSatisfying(ShareLinkService.UploadRejected.class,
                error -> { assertThat(error.status()).isEqualTo(507); assertThat(error.getMessage()).contains("2 KB"); });
        assertThat(storage.usedStorage()).isEqualTo(2048);
        assertThat(DisplayFormats.storage(2048, limits.storageQuota().toBytes())).isEqualTo("0.0 MB of 2 KB used");
    }

    void upload(int bytes) throws Exception {
        storage.storeUpload(new MockMultipartFile("file", UUID.randomUUID() + ".bin", "application/octet-stream",
                new byte[bytes]), null, false, false);
    }

    @Test void invalidCombinationsFailAndFormattingSupportsCustomSizes() {
        for (long[] sizes : new long[][]{{0, 10, 10, 20}, {11, 10, 20, 20}, {10, 10, 9, 20}, {10, 10, 10, 19}}) {
            assertThatThrownBy(() -> new TransferLimits(DataSize.ofBytes(sizes[0]), DataSize.ofBytes(sizes[1]),
                    DataSize.ofBytes(sizes[2]), DataSize.ofBytes(sizes[3]), DataSize.ofBytes(0), 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(DisplayFormats.expiration(null)).isEqualTo("Never expires");
        assertThat(DisplayFormats.size(1536)).isEqualTo("1.50 KB");
    }
}
