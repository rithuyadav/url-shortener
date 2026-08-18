package com.example.urlshortener.repository;

import com.example.urlshortener.model.ClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryClickEventRepositoryTest {

    private InMemoryClickEventRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryClickEventRepository();
    }

    @Test
    void countFor_unknownShortCode_isZero() {
        assertThat(repo.countFor("nope")).isZero();
    }

    @Test
    void record_incrementsTotalCount() {
        repo.record(new ClickEvent("abc", Instant.now(), "https://google.com", "ua", "iphash1"));
        repo.record(new ClickEvent("abc", Instant.now(), "https://google.com", "ua", "iphash2"));

        assertThat(repo.countFor("abc")).isEqualTo(2);
    }

    @Test
    void referrerBreakdown_groupsByReferrer_andBlankBecomesDirect() {
        repo.record(new ClickEvent("abc", Instant.now(), "https://google.com", "ua", "h1"));
        repo.record(new ClickEvent("abc", Instant.now(), "https://google.com", "ua", "h2"));
        repo.record(new ClickEvent("abc", Instant.now(), null, "ua", "h3"));
        repo.record(new ClickEvent("abc", Instant.now(), "", "ua", "h4"));

        var breakdown = repo.referrerBreakdown("abc");

        assertThat(breakdown).containsEntry("https://google.com", 2L);
        assertThat(breakdown).containsEntry("direct", 2L);
    }

    @Test
    void dailyBreakdown_groupsByUtcDate() {
        Instant fixedDay = Instant.parse("2026-01-15T10:00:00Z");
        repo.record(new ClickEvent("abc", fixedDay, "ref", "ua", "h1"));
        repo.record(new ClickEvent("abc", fixedDay.plusSeconds(3600), "ref", "ua", "h2"));

        var breakdown = repo.dailyBreakdown("abc");

        assertThat(breakdown).containsEntry("2026-01-15", 2L);
    }

    @Test
    void findRecent_returnsNewestFirst() {
        Instant t1 = Instant.now();
        Instant t2 = t1.plusSeconds(1);
        ClickEvent first = new ClickEvent("abc", t1, "ref1", "ua", "h1");
        ClickEvent second = new ClickEvent("abc", t2, "ref2", "ua", "h2");

        repo.record(first);
        repo.record(second);

        var recent = repo.findRecent("abc", 10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).isEqualTo(second); // most recently recorded first
        assertThat(recent.get(1)).isEqualTo(first);
    }

    @Test
    void findRecent_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            repo.record(new ClickEvent("abc", Instant.now(), "ref", "ua", "h" + i));
        }

        assertThat(repo.findRecent("abc", 2)).hasSize(2);
    }

    @Test
    void statsAreIsolatedPerShortCode() {
        repo.record(new ClickEvent("abc", Instant.now(), "ref", "ua", "h1"));
        repo.record(new ClickEvent("xyz", Instant.now(), "ref", "ua", "h2"));

        assertThat(repo.countFor("abc")).isEqualTo(1);
        assertThat(repo.countFor("xyz")).isEqualTo(1);
    }
}
