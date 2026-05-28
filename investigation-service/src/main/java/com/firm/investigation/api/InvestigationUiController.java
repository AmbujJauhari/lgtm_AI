package com.firm.investigation.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTML entry-point used by the Grafana derived-field link.
 *
 * <p>The Grafana link points here (rather than {@code /api/v1/investigate}) so the
 * browser immediately renders a "Building your dashboard..." page. The page then
 * calls {@code /api/v1/investigate?...&format=json} via fetch in the background,
 * cycles through reasonable status messages while waiting, and redirects to the
 * dashboard URL once the JSON response arrives.</p>
 *
 * <p>Without this intermediate, the browser sits on a blank loading state for the
 * 5-15 seconds the LangGraph pipeline takes — bad UX. With it, the user sees
 * what's happening.</p>
 */
@Controller
public class InvestigationUiController {

    @GetMapping(value = "/investigate", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> loadingPage(@RequestParam String logLine) {
        String encoded = URLEncoder.encode(logLine, StandardCharsets.UTF_8);
        return ResponseEntity.ok(loadingHtml(encoded));
    }

    private String loadingHtml(String encodedLogLine) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <title>Building your investigation…</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                           max-width: 640px; margin: 4em auto; padding: 0 1.5em;
                           color: #2a2a2a; background: #fafafa; }
                    @media (prefers-color-scheme: dark) {
                      body { color: #e6e6e6; background: #1b1b1b; }
                      .card { background: #262626 !important; border-color: #3a3a3a !important; }
                      .step.pending { color: #6a6a6a !important; }
                    }
                    .card { background: white; border: 1px solid #e1e1e1; border-radius: 8px;
                            padding: 2em; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
                    h1 { margin: 0 0 0.4em; font-size: 1.5em; }
                    .sub { margin: 0 0 1.6em; color: #888; font-size: 0.95em; }
                    .step { display: flex; align-items: center; padding: 0.5em 0; font-size: 1em; }
                    .step.pending { color: #aaa; }
                    .step.active { color: inherit; font-weight: 500; }
                    .step.done { color: #2c5; }
                    .icon { display: inline-block; width: 1.4em; margin-right: 0.6em; text-align: center; }
                    .spin { display: inline-block; width: 0.9em; height: 0.9em;
                            border: 2px solid currentColor; border-bottom-color: transparent;
                            border-radius: 50%%; animation: spin 0.8s linear infinite; }
                    @keyframes spin { to { transform: rotate(360deg); } }
                    .error { color: #c33; background: #fee; padding: 1em; border-radius: 6px;
                             margin-top: 1.5em; display: none; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>🔍 Building your investigation</h1>
                    <p class="sub">This usually takes 5–15 seconds. We'll redirect you to the dashboard automatically.</p>
                    <div id="steps">
                      <div class="step pending" data-step="0"><span class="icon">⏵</span>Loading panel catalogue</div>
                      <div class="step pending" data-step="1"><span class="icon">⏵</span>Searching past incidents (Atlas)</div>
                      <div class="step pending" data-step="2"><span class="icon">⏵</span>Reasoning about affected services</div>
                      <div class="step pending" data-step="3"><span class="icon">⏵</span>Fetching live trace and log data</div>
                      <div class="step pending" data-step="4"><span class="icon">⏵</span>Selecting panels</div>
                      <div class="step pending" data-step="5"><span class="icon">⏵</span>Publishing dashboard</div>
                    </div>
                    <div class="error" id="error"></div>
                  </div>
                  <script>
                    const steps = document.querySelectorAll('.step');
                    const errorBox = document.getElementById('error');
                    let current = 0;
                    let done = false;

                    function setStep(i) {
                      steps.forEach((el, idx) => {
                        el.classList.remove('pending', 'active', 'done');
                        if (idx < i)       { el.classList.add('done');    el.querySelector('.icon').textContent = '✓'; }
                        else if (idx === i){ el.classList.add('active');  el.querySelector('.icon').innerHTML = '<span class="spin"></span>'; }
                        else               { el.classList.add('pending'); el.querySelector('.icon').textContent = '⏵'; }
                      });
                    }
                    setStep(0);

                    // Advance through steps every ~2s while the fetch is in flight
                    const timer = setInterval(() => {
                      if (done) return;
                      if (current < steps.length - 1) { current++; setStep(current); }
                    }, 2200);

                    const params = new URLSearchParams(window.location.search);
                    const apiUrl = '/api/v1/investigate?format=json&' + params.toString();

                    fetch(apiUrl, { headers: { 'Accept': 'application/json' } })
                      .then(async r => {
                        const body = await r.json().catch(() => ({}));
                        if (!r.ok || !body.dashboardUrl) {
                          throw new Error(body.error || body.message || ('HTTP ' + r.status));
                        }
                        done = true;
                        clearInterval(timer);
                        steps.forEach(el => { el.classList.remove('pending','active'); el.classList.add('done'); el.querySelector('.icon').textContent = '✓'; });
                        window.location.replace(body.dashboardUrl);
                      })
                      .catch(err => {
                        done = true;
                        clearInterval(timer);
                        errorBox.style.display = 'block';
                        errorBox.textContent = 'Investigation failed: ' + err.message;
                      });
                  </script>
                </body>
                </html>
                """;
    }
}
