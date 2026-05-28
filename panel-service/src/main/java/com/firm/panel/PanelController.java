package com.firm.panel;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/panels")
public class PanelController {

    private final PanelLibrary panelLibrary;

    public PanelController(PanelLibrary panelLibrary) {
        this.panelLibrary = panelLibrary;
    }

    @GetMapping
    public Mono<List<PanelTemplate>> allPanels() {
        return Mono.just(panelLibrary.allPanels());
    }

    @GetMapping(value = "/descriptions", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> descriptions() {
        return Mono.just(panelLibrary.descriptions());
    }

    @GetMapping("/{panelId}")
    public Mono<ResponseEntity<PanelTemplate>> panel(@PathVariable String panelId) {
        return Mono.just(panelLibrary.allPanels().stream()
                .filter(p -> p.panelId().equals(panelId))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build()));
    }
}
