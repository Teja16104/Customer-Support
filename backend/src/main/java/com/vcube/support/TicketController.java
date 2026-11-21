package com.vcube.support;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.vcube.support.Account.Role.*;
import static com.vcube.support.TicketService.*;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService tickets;
    private final TicketAi ai;
    private final JdbcTemplate db;

    TicketController(TicketService tickets, TicketAi ai, JdbcTemplate db) {
        this.tickets = tickets;
        this.ai = ai;
        this.db = db;
    }

    record Complaint(@NotBlank @Size(min = 3, max = 160) String subject,
                     @NotBlank @Size(min = 10, max = 8000) String description) {}
    record State(@NotNull Status status, @Size(max = 2000) String resolutionSummary) {}
    record Triage(@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String categoryCode, Priority priority,
                  @NotBlank @Size(min = 3, max = 255) String reason) {}
    record Assignment(@JsonProperty(required = true) String agentId,
                      @NotBlank @Size(min = 3, max = 255) String reason) {}
    record Message(@NotBlank @Size(max = 5000) String body, @NotNull Visibility visibility) {}
    enum Visibility { PUBLIC, INTERNAL }
    record Rating(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 1000) String comment) {}
    record Draft(@NotNull TicketAi.Tone tone) {}

    @PostMapping
    ResponseEntity<?> submit(Authentication user, @RequestHeader("Idempotency-Key") UUID key,
                             @Valid @RequestBody Complaint body) {
        var result = tickets.submit(tickets.actor(user, CUSTOMER), key, body.subject(), body.description());
        var ticket = result.ticket();
        return ResponseEntity.status(result.replay() ? 200 : 201).eTag(etag(ticket))
                .location(URI.create("/api/v1/tickets/" + ticket.get("key"))).body(ticket);
    }

    @GetMapping
    Map<String, Object> list(Authentication user, @RequestParam(required = false) Status status,
            @RequestParam(required = false) Priority priority, @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String scope, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return tickets.list(tickets.actor(user), status, priority, categoryCode, scope, page, size);
    }

    @GetMapping("/{key}")
    ResponseEntity<?> detail(Authentication user, @PathVariable String key) {
        return ticket(tickets.get(tickets.actor(user), key));
    }

    @PatchMapping("/{key}/status")
    ResponseEntity<?> status(Authentication user, @PathVariable String key,
            @RequestHeader(value = "If-Match", required = false) String tag, @Valid @RequestBody State body) {
        return ticket(tickets.status(tickets.actor(user), key, tag, body.status(), body.resolutionSummary()));
    }

    @PatchMapping("/{key}/triage")
    ResponseEntity<?> triage(Authentication user, @PathVariable String key,
            @RequestHeader(value = "If-Match", required = false) String tag, @Valid @RequestBody Triage body) {
        return ticket(tickets.triage(tickets.actor(user, AGENT, ADMIN), key, tag,
                body.categoryCode(), body.priority(), body.reason()));
    }

    @PutMapping("/{key}/assignment")
    ResponseEntity<?> assignment(Authentication user, @PathVariable String key,
            @RequestHeader(value = "If-Match", required = false) String tag, @Valid @RequestBody Assignment body) {
        Long agent = null;
        if (body.agentId() != null) {
            try { agent = Long.valueOf(body.agentId()); }
            catch (NumberFormatException failure) { throw fail(400, "Invalid agent ID."); }
        }
        return ticket(tickets.assign(tickets.actor(user, ADMIN), key, tag, agent, body.reason()));
    }

    @GetMapping("/{key}/routing-candidates")
    List<Map<String, Object>> candidates(Authentication user, @PathVariable String key) {
        var row = tickets.scoped(tickets.actor(user, ADMIN), key, false);
        active(row);
        return tickets.routing(number(row.get("category_id"))).stream()
                .map(agent -> fields("agent", person(agent.get("id"), agent.get("display_name")),
                        "proficiency", agent.get("proficiency"), "activeTicketCount", agent.get("active_count"),
                        "maxActiveTickets", agent.get("max_active_tickets"))).toList();
    }

    @GetMapping("/{key}/assignments")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    List<Map<String, Object>> assignments(Authentication user, @PathVariable String key) {
        var row = tickets.scoped(tickets.actor(user, AGENT, ADMIN), key, false);
        return db.queryForList("""
                SELECT u.display_name AS agentName,a.source,a.reason,a.assigned_at AS assignedAt,a.ended_at AS endedAt
                FROM ticket_assignments a JOIN users u ON u.id=a.agent_id WHERE a.ticket_id=? ORDER BY a.id DESC
                """, row.get("id"));
    }

    @GetMapping("/{key}/comments")
    List<Map<String, Object>> comments(Authentication user, @PathVariable String key) {
        return tickets.comments(tickets.actor(user), key);
    }

    @PostMapping("/{key}/comments")
    ResponseEntity<?> comment(Authentication user, @PathVariable String key,
            @RequestHeader(value = "If-Match", required = false) String tag, @Valid @RequestBody Message body) {
        var updated = tickets.comment(tickets.actor(user), key, tag, body.body(), body.visibility().name());
        return ResponseEntity.status(201).eTag(etag(updated)).body(updated);
    }

    @GetMapping("/{key}/events")
    List<Map<String, Object>> events(Authentication user, @PathVariable String key) {
        return tickets.history(tickets.actor(user), key);
    }

    @GetMapping("/{key}/feedback")
    Map<String, Object> feedback(Authentication user, @PathVariable String key) {
        return tickets.feedback(tickets.actor(user), key);
    }

    @PostMapping("/{key}/feedback")
    ResponseEntity<?> feedback(Authentication user, @PathVariable String key,
            @RequestHeader(value = "If-Match", required = false) String tag, @Valid @RequestBody Rating body) {
        var updated = tickets.feedback(tickets.actor(user, CUSTOMER), key, tag, body.rating(), body.comment());
        return ResponseEntity.status(201).eTag(etag(updated)).body(updated);
    }

    @GetMapping("/{key}/similar")
    List<Map<String, Object>> similar(Authentication user, @PathVariable String key) {
        return tickets.similar(tickets.actor(user, AGENT, ADMIN), key);
    }

    @PostMapping("/{key}/ai/response")
    Map<String, Object> response(Authentication user, @PathVariable String key, @Valid @RequestBody Draft body) {
        return ai.suggest(tickets.actor(user, AGENT, ADMIN), key, TicketAi.Feature.RESPONSE, body.tone());
    }

    @PostMapping("/{key}/ai/{feature:classification|sentiment|priority|resolution}")
    Map<String, Object> suggest(Authentication user, @PathVariable String key, @PathVariable String feature) {
        return ai.suggest(tickets.actor(user, AGENT, ADMIN), key,
                TicketAi.Feature.valueOf(feature.toUpperCase(Locale.ROOT)), null);
    }

    private ResponseEntity<?> ticket(Map<String, Object> data) {
        return ResponseEntity.ok().eTag(etag(data)).body(data);
    }
}