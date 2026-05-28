package com.firm.investigation.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final GrafanaDashboardReader dashboardReader;
    private final DashboardMemoryWriter memoryWriter;

    public FeedbackController(GrafanaDashboardReader dashboardReader, DashboardMemoryWriter memoryWriter) {
        this.dashboardReader = dashboardReader;
        this.memoryWriter = memoryWriter;
    }

    @GetMapping(value = "/feedback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> form(@RequestParam String uid) {
        return ResponseEntity.ok(formHtml(uid, null));
    }

    @PostMapping(value = "/feedback",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> submit(@RequestParam String uid,
                                          @RequestParam("feedback") String feedback) {
        try {
            GrafanaDashboardReader.DashboardSnapshot snapshot = dashboardReader.fetch(uid);
            DashboardMemoryWriter.DashboardMemoryRecord record = new DashboardMemoryWriter.DashboardMemoryRecord(
                    uid,
                    snapshot.description(),
                    snapshot.errorCategory(),
                    snapshot.service(),
                    snapshot.appcode(),
                    snapshot.finalPanelDescriptors(),
                    feedback
            );
            memoryWriter.write(record);
            return ResponseEntity.ok(thanksHtml(uid));
        } catch (Exception e) {
            log.error("Failed to save feedback for uid={}: {}", uid, e.getMessage(), e);
            return ResponseEntity.status(500).body(formHtml(uid,
                    "Failed to save your feedback: " + e.getMessage() + ". Please try again."));
        }
    }

    private String formHtml(String uid, String errorMessage) {
        String error = errorMessage == null ? "" :
                "<div style='background:#fee;padding:1em;margin-bottom:1em;border:1px solid #c00;'>" +
                escape(errorMessage) + "</div>";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Investigation feedback</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                           max-width: 720px; margin: 2em auto; padding: 0 1em; color: #333; }
                    h1 { font-size: 1.4em; }
                    label { display: block; margin-top: 1em; font-weight: 600; }
                    textarea { width: 100%%; min-height: 200px; padding: 0.5em;
                               font-family: inherit; font-size: 1em; box-sizing: border-box; }
                    button { margin-top: 1em; padding: 0.6em 1.4em; background: #2c5; color: white;
                             border: 0; border-radius: 4px; cursor: pointer; font-size: 1em; }
                    .uid { color: #888; font-size: 0.85em; }
                  </style>
                </head>
                <body>
                  %s
                  <h1>📝 Investigation feedback</h1>
                  <p>Tell us what you learned — what panels were useful, what was actually wrong,
                     what we missed. Your feedback teaches the system for the next investigation
                     of a similar error.</p>
                  <p class="uid">Dashboard: <code>%s</code></p>
                  <form method="POST" action="/feedback">
                    <input type="hidden" name="uid" value="%s">
                    <label for="feedback">Your learnings:</label>
                    <textarea name="feedback" id="feedback" placeholder="e.g. Root cause was a stale cache after deploy. gc_pause_duration was useful, latency_p95 wasn't relevant for this error."></textarea>
                    <button type="submit">Save learnings</button>
                  </form>
                </body>
                </html>
                """.formatted(error, escape(uid), escape(uid));
    }

    private String thanksHtml(String uid) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Feedback saved</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif;
                           max-width: 600px; margin: 2em auto; padding: 0 1em; color: #333; }
                    .ok { background: #efe; padding: 1.5em; border: 1px solid #2c5; border-radius: 4px; }
                  </style>
                </head>
                <body>
                  <div class="ok">
                    <h1>✅ Thanks — learnings saved</h1>
                    <p>Your feedback for dashboard <code>%s</code> has been recorded.
                       Future investigations of similar errors will benefit from it.</p>
                    <p>You can close this tab.</p>
                  </div>
                </body>
                </html>
                """.formatted(escape(uid));
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
