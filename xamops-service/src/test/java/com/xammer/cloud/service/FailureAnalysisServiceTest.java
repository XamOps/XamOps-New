package com.xammer.cloud.service;

import com.xammer.cloud.dto.cicd.FailureAnalysisDto;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FailureAnalysisServiceTest {

    private final FailureAnalysisService service = new FailureAnalysisService(null, null, null, null);

    @Test
    void javaException_extracted() {
        String log = "Starting build...\nLine2\nLine3\n"
                + "java.lang.NullPointerException: Cannot read field\n"
                + "  at com.example.Foo.bar(Foo.java:42)\n"
                + "  at com.example.Main.main(Main.java:10)\n"
                + "Build complete.";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets()).isNotEmpty();
        assertThat(result.isFallback()).isFalse();
        assertThat(result.getSnippets().get(0).getLines())
                .anyMatch(l -> l.contains("NullPointerException"));
    }

    @Test
    void npmError_extracted() {
        String log = "npm ERR! code ELIFECYCLE\nnpm ERR! errno 1\n";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets()).isNotEmpty();
        assertThat(result.getSnippets().get(0).getLines()).anyMatch(l -> l.contains("npm ERR!"));
    }

    @Test
    void noMatch_returnsFallbackLastLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) sb.append("log line ").append(i).append("\n");
        FailureAnalysisDto result = service.parseLog(sb.toString());
        assertThat(result.isFallback()).isTrue();
        assertThat(result.getSnippets()).hasSize(1);
        assertThat(result.getSnippets().get(0).getLines()).hasSize(20);
        assertThat(result.getSnippets().get(0).getLines().get(0)).contains("log line 11");
    }

    @Test
    void maxThreeSnippets_returned() {
        String log = "Exception: a\nException: b\nException: c\nException: d\n";
        FailureAnalysisDto result = service.parseLog(log);
        assertThat(result.getSnippets().size()).isLessThanOrEqualTo(3);
    }
}
