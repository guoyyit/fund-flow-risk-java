package com.github.guoyyit.fundflowriskjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    @DynamicPropertySource
    static void walProps(DynamicPropertyRegistry registry) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("wal-it");
        registry.add("fundflow.wal.path", () -> dir.resolve("it.wal").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @SuppressWarnings("unchecked")
    void endToEndFlow() {
        List<Map<String, Object>> batch = List.of(
                Map.of("txId", "t1", "from", "a", "to", "b", "amount", 100, "timestamp", 1000),
                Map.of("txId", "t2", "from", "b", "to", "c", "amount", 100, "timestamp", 2000),
                Map.of("txId", "t3", "from", "c", "to", "a", "amount", 100, "timestamp", 3000));

        Map<String, Object> applyResult = rest.postForObject(url("/api/transfers"), batch, Map.class);
        assertEquals(3, applyResult.get("accepted"));

        // duplicate apply is idempotent
        Map<String, Object> dupResult = rest.postForObject(url("/api/transfers"), batch, Map.class);
        assertEquals(0, dupResult.get("accepted"));
        assertEquals(3, dupResult.get("duplicates"));

        Map<String, Object> stats = rest.getForObject(url("/api/graph/stats"), Map.class);
        assertEquals(3, stats.get("edges"));
        assertEquals(3, stats.get("addresses"));

        List<Map<String, Object>> risks = rest.getForObject(url("/api/risks"), List.class);
        assertNotNull(risks);
        assertTrue(risks.stream().anyMatch(h -> "CYCLE".equals(h.get("rule"))));

        // parameter override: maxHops=2 removes the 3-hop cycle hit
        List<Map<String, Object>> noRisks = rest.getForObject(url("/api/risks?maxHops=2"), List.class);
        assertNotNull(noRisks);
        assertTrue(noRisks.stream().noneMatch(h -> "CYCLE".equals(h.get("rule"))));

        // rollback
        Boolean deleted = rest.exchange(url("/api/transfers/t3"), HttpMethod.DELETE, null, Boolean.class).getBody();
        assertEquals(Boolean.TRUE, deleted);
        List<Map<String, Object>> afterDelete = rest.getForObject(url("/api/risks"), List.class);
        assertNotNull(afterDelete);
        assertTrue(afterDelete.stream().noneMatch(h -> "CYCLE".equals(h.get("rule"))));
    }
}
