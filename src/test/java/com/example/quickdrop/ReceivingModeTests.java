package com.example.quickdrop;

import com.example.quickdrop.receive.ReceiveLink;
import com.example.quickdrop.receive.ReceiveLinkRepository;
import com.example.quickdrop.receive.ReceiveLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:receiving-mode-test")
@Transactional
class ReceivingModeTests {

    @Autowired
    private ReceiveLinkService receiveLinkService;

    @Autowired
    private ReceiveLinkRepository receiveLinkRepository;

    @BeforeEach
    void clearReceivingSessions() {
        receiveLinkRepository.deleteAllInBatch();
    }

    @Test
    void startingReceivingCreatesTheCurrentSession() {
        ReceiveLinkService.LinkDetails started =
                receiveLinkService.start(Duration.ofHours(1));

        assertThat(receiveLinkService.findCurrentActive())
                .contains(started);
        assertThat(receiveLinkService.findActive(started.token()))
                .contains(started);
    }

    @Test
    void startingAgainRevokesThePreviousSession() {
        ReceiveLinkService.LinkDetails first =
                receiveLinkService.start(Duration.ofHours(1));
        ReceiveLinkService.LinkDetails second =
                receiveLinkService.start(Duration.ofHours(1));

        assertThat(receiveLinkService.findActive(first.token()))
                .isEmpty();
        assertThat(receiveLinkService.findCurrentActive())
                .contains(second);
        assertThat(receiveLinkRepository.count()).isEqualTo(1);
    }

    @Test
    void stoppingReceivingRevokesTheCurrentSession() {
        ReceiveLinkService.LinkDetails started =
                receiveLinkService.start(Duration.ofHours(1));

        assertThat(receiveLinkService.stop()).isTrue();
        assertThat(receiveLinkService.findCurrentActive()).isEmpty();
        assertThat(receiveLinkService.findActive(started.token()))
                .isEmpty();
        assertThat(receiveLinkService.stop()).isFalse();
    }

    @Test
    void expiredSessionCannotReceiveFiles() {
        ReceiveLink expired = new ReceiveLink(
                "expired-token",
                Instant.now().minusSeconds(1)
        );
        receiveLinkRepository.saveAndFlush(expired);

        assertThat(receiveLinkService.findCurrentActive()).isEmpty();
        assertThat(receiveLinkRepository.count()).isZero();
    }

    @Test
    void receivingTimeMustBePositive() {
        assertThatThrownBy(() ->
                receiveLinkService.start(Duration.ZERO)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
