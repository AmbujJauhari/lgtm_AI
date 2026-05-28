package com.firm.investigation.feedback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackControllerTest {

    @Mock private GrafanaDashboardReader reader;
    @Mock private DashboardMemoryWriter writer;

    private FeedbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FeedbackController(reader, writer);
    }

    @Test
    void form_renders_withUidInBody() {
        ResponseEntity<String> response = controller.form("inv-abc-123");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("inv-abc-123");
        assertThat(response.getBody()).contains("<form");
        assertThat(response.getBody()).contains("/feedback");
    }

    @Test
    void submit_happyPath_returnsThanksPage_andCallsWriter() {
        GrafanaDashboardReader.DashboardSnapshot snapshot = new GrafanaDashboardReader.DashboardSnapshot(
                "Investigation", "log line text", "AT4278", "svc", "NPE", null,
                List.of("text:Logs"));
        when(reader.fetch("inv-1")).thenReturn(snapshot);

        ResponseEntity<String> response = controller.submit("inv-1", "great panels");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Thanks").contains("inv-1");
        verify(writer).write(any());
    }

    @Test
    void submit_writerFailure_returnsError() {
        GrafanaDashboardReader.DashboardSnapshot snapshot = new GrafanaDashboardReader.DashboardSnapshot(
                "Investigation", "log", "AT4278", "svc", "NPE", null, List.of());
        when(reader.fetch("inv-1")).thenReturn(snapshot);
        doThrow(new RuntimeException("neo4j down")).when(writer).write(any());

        ResponseEntity<String> response = controller.submit("inv-1", "feedback");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("Failed");
    }

    @Test
    void submit_readerFailure_returnsError() {
        when(reader.fetch("inv-x")).thenThrow(new RuntimeException("grafana 404"));

        ResponseEntity<String> response = controller.submit("inv-x", "anything");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("Failed");
    }
}
