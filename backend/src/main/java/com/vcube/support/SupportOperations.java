package com.vcube.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

import static com.vcube.support.Account.Role.*;
import static com.vcube.support.TicketService.*;

@RestController
@RequestMapping("/api/v1")
public class SupportOperations {
    private final JdbcTemplate db;
    private final TicketService tickets;
    private final ObjectMapper json;

    SupportOperations(JdbcTemplate db, TicketService tickets, ObjectMapper json) {
        this.db = db;
        this.tickets = tickets;
        this.json = json;
    }

    record Skill(@NotBlank String categoryCode, @NotNull @Min(1) @Max(3) Integer proficiency) {}
    record Routing(@NotNull Boolean routingAvailable, @NotNull @Min(1) @Max(100) Integer maxActiveTickets,
                   @NotNull @Size(max = 50) List<@NotNull @Valid Skill> skills) {}
    record CategorySettings(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String code,
            @NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 500) String description,
            @NotNull Boolean enabled, @NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> triageKeywords,
            @NotBlank @Size(max = 5000) String resolutionPlaybook) {}

    @GetMapping("/admin/agents")
    List<Map<String, Object>> agents(Authentication user) {
        tickets.actor(user, ADMIN);
        return db.queryForList("""
                SELECT id,display_name,max_active_tickets,routing_available
                FROM users WHERE role='AGENT' AND enabled ORDER BY display_name
                """).stream().map(row -> {
                    long id = number(row.get("id"));
                    var skills = db.queryForList("""
                            SELECT c.code AS categoryCode,s.proficiency FROM agent_categories s
                            JOIN categories c ON c.id=s.category_id WHERE s.agent_id=? AND c.enabled ORDER BY c.code
                            """, id);
                    return fields("id", Long.toString(id), "displayName", row.get("display_name"),
                            "maxActiveTickets", row.get("max_active_tickets"), "activeTicketCount", tickets.load(id),
                            "routingAvailable", truth(row.get("routing_available")), "skills", skills);
                }).toList();
    }

    @PutMapping("/admin/agents/{agentId}/routing")
    @Transactional
    Map<String, Object> routing(Authentication user, @PathVariable long agentId, @Valid @RequestBody Routing body) {
        tickets.actor(user, ADMIN);
        tickets.lockAgent(agentId);
        Set<String> seen = new HashSet<>();
        for (Skill skill : body.skills()) {
            if (!seen.add(skill.categoryCode())) throw fail(400, "Duplicate category in routing setup.");
        }

        db.update("UPDATE users SET routing_available=?,max_active_tickets=? WHERE id=?",
                body.routingAvailable(), body.maxActiveTickets(), agentId);
        db.update("DELETE FROM agent_categories WHERE agent_id=?", agentId);
        for (Skill skill : body.skills()) {
            db.update("INSERT INTO agent_categories(agent_id,category_id,proficiency) VALUES (?,?,?)",
                    agentId, tickets.category(skill.categoryCode()), skill.proficiency());
        }
        return agents(user).stream().filter(row -> row.get("id").equals(Long.toString(agentId)))
                .findFirst().orElseThrow(() -> fail(404, "Agent not found."));
    }

    @GetMapping("/admin/categories")
    List<Map<String, Object>> categories(Authentication user) {
        tickets.actor(user, ADMIN);
        return db.queryForList("SELECT * FROM categories ORDER BY name").stream().map(this::category).toList();
    }

    @PostMapping("/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> addCategory(Authentication user, @Valid @RequestBody CategorySettings body) {
        tickets.actor(user, ADMIN);
        try {
            db.update("""
                    INSERT INTO categories(code,name,description,enabled,triage_keywords,resolution_playbook)
                    VALUES (?,?,?,?,?,?)
                    """, body.code(), body.name().strip(), body.description().strip(), body.enabled(),
                    write(body.triageKeywords()), body.resolutionPlaybook().strip());
        } catch (org.springframework.dao.DuplicateKeyException failure) {
            throw fail(409, "That category code already exists.");
        }
        return category(tickets.one("SELECT * FROM categories WHERE code=?", body.code()));
    }

    @PutMapping("/admin/categories/{code}")
    Map<String, Object> replaceCategory(Authentication user, @PathVariable String code,
                                       @Valid @RequestBody CategorySettings body) {
        tickets.actor(user, ADMIN);
        if (!code.equals(body.code())) throw fail(400, "Keep the category code unchanged in the request.");
        if (code.equals("UNCATEGORIZED") && !body.enabled()) throw fail(409, "Needs triage must stay enabled.");
        if (db.update("""
                UPDATE categories SET name=?,description=?,enabled=?,triage_keywords=?,resolution_playbook=? WHERE code=?
                """, body.name().strip(), body.description().strip(), body.enabled(),
                write(body.triageKeywords()), body.resolutionPlaybook().strip(), code) == 0) {
            throw fail(404, "Category not found.");
        }
        return category(tickets.one("SELECT * FROM categories WHERE code=?", code));
    }

    private Map<String, Object> category(Map<String, Object> row) {
        try {
            return fields("code", row.get("code"), "name", row.get("name"), "description", row.get("description"),
                    "enabled", truth(row.get("enabled")), "triageKeywords", json.readTree(row.get("triage_keywords").toString()),
                    "resolutionPlaybook", row.get("resolution_playbook"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 30000)
    void scanAlerts() {
        addAlerts("URGENT", "t.priority='URGENT'");
        addAlerts("NEGATIVE_HIGH", "t.priority IN ('HIGH','URGENT') AND t.sentiment='NEGATIVE'");
        addAlerts("FIRST_RESPONSE_BREACHED", "t.first_response_at IS NULL AND t.first_response_due_at<=UTC_TIMESTAMP(6)");
        addAlerts("FIRST_RESPONSE_DUE_SOON",
                "t.first_response_at IS NULL AND t.first_response_due_at BETWEEN UTC_TIMESTAMP(6) AND UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE");
        addAlerts("RESOLUTION_BREACHED", "t.resolution_due_at<=UTC_TIMESTAMP(6)");
        addAlerts("RESOLUTION_DUE_SOON", "t.resolution_due_at BETWEEN UTC_TIMESTAMP(6) AND UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE");
    }

    private void addAlerts(String type, String eligibility) {
        db.update("""
                INSERT INTO sla_alerts(ticket_id,sla_cycle,alert_type)
                SELECT t.id,t.sla_cycle,? FROM tickets t WHERE t.status IN %s AND %s
                  AND NOT EXISTS (SELECT 1 FROM sla_alerts a WHERE a.ticket_id=t.id
                                  AND a.sla_cycle=t.sla_cycle AND a.alert_type=?)
                ON DUPLICATE KEY UPDATE id=sla_alerts.id
                """.formatted(ACTIVE, eligibility), type, type);
    }

    @GetMapping("/alerts")
    Map<String, Object> alerts(Authentication user, @RequestParam(defaultValue = "false") boolean acknowledged) {
        Account actor = tickets.actor(user, AGENT, ADMIN);
        String scope = actor.role == AGENT ? " AND assignment.agent_id=?" : "";
        var rows = db.queryForList("""
                SELECT s.id,t.ticket_key AS ticketKey,s.alert_type AS type,s.sla_cycle AS slaCycle,
                       s.created_at AS createdAt,s.acknowledged_at AS acknowledgedAt
                FROM sla_alerts s JOIN tickets t ON t.id=s.ticket_id
                LEFT JOIN ticket_assignments assignment ON assignment.active_ticket_id=t.id
                WHERE s.sla_cycle=t.sla_cycle AND t.status IN
                """ + " " + ACTIVE + " AND s.acknowledged_at " + (acknowledged ? "IS NOT NULL" : "IS NULL")
                + scope + " ORDER BY s.id DESC LIMIT 100", actor.role == AGENT ? new Object[]{actor.id} : new Object[0]);
        rows.forEach(row -> row.put("id", row.get("id").toString()));
        return fields("items", rows, "page", 0, "size", 100, "total", rows.size());
    }

    @PutMapping("/alerts/{id}/acknowledgement")
    @Transactional
    Map<String, Object> acknowledge(Authentication user, @PathVariable long id) {
        Account actor = tickets.actor(user, AGENT, ADMIN);
        var alert = tickets.one("""
                SELECT ticket_key FROM sla_alerts s JOIN tickets t ON t.id=s.ticket_id
                WHERE s.id=? AND s.sla_cycle=t.sla_cycle
                """, id);
        var ticket = tickets.scoped(actor, alert.get("ticket_key").toString(), true);
        active(ticket);
        if (db.update("""
                UPDATE sla_alerts SET acknowledged_by=?,acknowledged_at=UTC_TIMESTAMP(6)
                WHERE id=? AND sla_cycle=? AND acknowledged_at IS NULL
                """, actor.id, id, ticket.get("sla_cycle")) == 0) {
            tickets.one("SELECT id FROM sla_alerts WHERE id=? AND sla_cycle=?", id, ticket.get("sla_cycle"));
        }
        return tickets.one("SELECT id,alert_type AS type,acknowledged_at AS acknowledgedAt FROM sla_alerts WHERE id=?", id);
    }

    @GetMapping("/analytics/summary")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Map<String, Object> summary(Authentication user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        tickets.actor(user, ADMIN);
        return report(fromDate, toDate);
    }

    @GetMapping("/analytics/export")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ResponseEntity<?> export(Authentication user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "csv") String format) {
        tickets.actor(user, ADMIN);
        if (!Set.of("csv", "json").contains(format)) throw fail(400, "Choose csv or json.");

        var report = report(fromDate, toDate);
        var headers = ResponseEntity.ok().header("Content-Disposition",
                "attachment; filename=\"support-" + fromDate + "." + format + "\"");
        if (format.equals("json")) return headers.contentType(MediaType.APPLICATION_JSON).body(report);

        var csv = new StringBuilder("section,metric,dimension,value\r\n");
        report.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    var row = (Map<?, ?>) item;
                    String dimension = switch (key) {
                        case "daily" -> row.get("date").toString();
                        case "categoryVolume" -> row.get("categoryCode").toString();
                        case "aiUsage" -> row.get("feature").toString();
                        case "backlogByStatus" -> row.get("status").toString();
                        default -> ((Map<?, ?>) row.get("agent")).get("id").toString();
                    };
                    row.forEach((metric, amount) -> {
                        if (amount instanceof Number) {
                            csv.append(key).append(',').append(metric).append(',')
                                    .append(dimension).append(',').append(amount).append("\r\n");
                        }
                    });
                }
            } else {
                csv.append("summary,").append(key).append(",,").append(value == null ? "" : value).append("\r\n");
            }
        });
        return headers.contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv.toString());
    }

    private Map<String, Object> report(LocalDate from, LocalDate to) {
        if (!from.isBefore(to) || ChronoUnit.DAYS.between(from, to) > 90 || from.getYear() < 1000 || to.getYear() > 9999) {
            throw fail(400, "Choose a UTC date range of 1 to 90 days.");
        }
        var starts = daily("tickets", "1=1", from, to);
        var finishes = daily("ticket_events", "event_type='STATUS_CHANGED' AND to_status='RESOLVED'", from, to);
        var backlog = db.queryForList("SELECT status,COUNT(*) AS count FROM tickets WHERE status IN " + ACTIVE + " GROUP BY status");
        var categories = db.queryForList("""
                SELECT c.code AS categoryCode,COUNT(*) AS count FROM tickets t
                JOIN categories c ON t.category_id=c.id WHERE t.created_at>=? AND t.created_at<?
                GROUP BY c.code ORDER BY count DESC,c.code
                """, from, to);
        var ai = db.queryForList("""
                SELECT feature,SUM(source='MODEL') AS modelRuns,SUM(source='FALLBACK') AS fallbackRuns
                FROM ai_runs WHERE created_at>=? AND created_at<? GROUP BY feature
                """, from, to);
        var ratings = tickets.one("SELECT COUNT(*) AS total,AVG(rating) AS rating FROM ticket_feedback WHERE created_at>=? AND created_at<?",
                from, to);
        var reply = timing("event_type='FIRST_RESPONSE'", from, to);
        var resolution = timing("event_type='STATUS_CHANGED' AND to_status='RESOLVED'", from, to);
        long eligible = number(resolution.get("samples"));
        long met = number(resolution.get("met"));
        var workload = db.queryForList("SELECT id,display_name,max_active_tickets FROM users WHERE role='AGENT' AND enabled")
                .stream().map(row -> fields("agent", person(row.get("id"), row.get("display_name")),
                        "activeTicketCount", tickets.load(number(row.get("id"))), "maxActiveTickets", row.get("max_active_tickets"))).toList();

        return fields("fromDate", from, "toDate", to, "asOf", Instant.now(),
                "createdTickets", starts.values().stream().mapToLong(Long::longValue).sum(),
                "resolvedCycles", finishes.values().stream().mapToLong(Long::longValue).sum(),
                "openBacklog", backlog.stream().mapToLong(row -> number(row.get("count"))).sum(),
                "backlogByStatus", backlog, "categoryVolume", categories, "aiUsage", ai, "agentWorkload", workload,
                "daily", from.datesUntil(to).map(day -> fields("date", day, "createdTickets", starts.getOrDefault(day, 0L),
                        "resolvedCycles", finishes.getOrDefault(day, 0L))).toList(),
                "feedbackCount", ratings.get("total"), "averageRating", ratings.get("rating"),
                "firstResponseSamples", reply.get("samples"), "medianFirstResponseMinutes", reply.get("median"),
                "resolutionSamples", eligible, "medianResolutionMinutes", resolution.get("median"),
                "slaEligibleCycles", eligible, "slaMetCycles", met, "resolutionSlaMetPct",
                eligible == 0 ? null : Math.round(1000.0 * met / eligible) / 10.0);
    }

    private Map<String, Object> timing(String condition, LocalDate from, LocalDate to) {
        return tickets.one("""
                WITH samples AS (
                    SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(details,'$.seconds')) AS SIGNED) AS seconds,
                           JSON_UNQUOTE(JSON_EXTRACT(details,'$.met'))='true' AS met
                    FROM ticket_events WHERE %s AND created_at>=? AND created_at<?
                    AND JSON_CONTAINS_PATH(details,'all','$.seconds','$.met')
                ), ranked AS (
                    SELECT seconds,met,ROW_NUMBER() OVER(ORDER BY seconds) AS seq,COUNT(*) OVER() AS amount FROM samples
                )
                SELECT COUNT(*) AS samples,COALESCE(SUM(met),0) AS met,
                       AVG(CASE WHEN seq IN (FLOOR((amount+1)/2),FLOOR((amount+2)/2)) THEN seconds END)/60.0 AS median
                FROM ranked
                """.formatted(condition), from, to);
    }

    private Map<LocalDate, Long> daily(String table, String condition, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> result = new HashMap<>();
        db.queryForList("""
                SELECT DATE(created_at) AS day,COUNT(*) AS amount FROM %s
                WHERE %s AND created_at>=? AND created_at<? GROUP BY DATE(created_at)
                """.formatted(table, condition), from, to)
                .forEach(row -> result.put(LocalDate.parse(row.get("day").toString()), number(row.get("amount"))));
        return result;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }

    @Bean
    ApplicationRunner provisionStaff(AccountRepository users, PasswordEncoder passwords,
            @Value("${support.admin.email}") String adminEmail, @Value("${support.admin.password}") String adminPassword,
            @Value("${support.agent.email}") String agentEmail, @Value("${support.agent.password}") String agentPassword,
            @Value("${support.demo.password}") String demoPassword) {
        return arguments -> {
            if (!adminEmail.isBlank()) account(users, passwords, adminEmail, "Support Admin", ADMIN, adminPassword);
            if (!agentEmail.isBlank()) account(users, passwords, agentEmail, "Support Agent", AGENT, agentPassword);
            if (demoPassword.isBlank()) return;

            account(users, passwords, "admin@example.test", "Sam Admin", ADMIN, demoPassword);
            Account billing = account(users, passwords, "agent@example.test", "Mira Billing", AGENT, demoPassword);
            Account technical = account(users, passwords, "technical@example.test", "Arun Technical", AGENT, demoPassword);
            Account customer = account(users, passwords, "customer@example.test", "Isha Customer", CUSTOMER, demoPassword);
            addSkill(billing.id, "BILLING");
            addSkill(technical.id, "TECHNICAL");

            String[][] samples = {
                    {"Charged twice for my subscription", "My invoice shows two payments. Please verify the duplicate charge."},
                    {"Checkout outage affects all users", "The checkout service is unavailable for all users. This is unacceptable."},
                    {"Package arrived without a tracking update", "My delivery tracking says pending even though the package arrived."},
                    {"Locked out after resetting password", "The password reset link expired and I cannot access my account."},
                    {"Feature request for exports", "I would appreciate a feature that exports invoices by month."},
                    {"Refund missing from invoice", "The refund has not appeared on my billing invoice."}
            };
            for (String[] sample : samples) {
                UUID key = UUID.nameUUIDFromBytes(("demo:" + sample[0]).getBytes(StandardCharsets.UTF_8));
                tickets.submit(customer, key, sample[0], sample[1]);
            }
        };
    }

    private Account account(AccountRepository users, PasswordEncoder encoder, String email, String name,
                            Account.Role role, String password) {
        if (password.length() < 12 || password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Bootstrap and demo passwords must be 12-64 characters and at most 72 UTF-8 bytes.");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        var account = users.findByEmail(normalized).orElseGet(() -> {
            var created = new Account(normalized, name, encoder.encode(password));
            created.role = role;
            var saved = users.saveAndFlush(created);
            if (role == AGENT) db.update("UPDATE users SET routing_available=TRUE WHERE id=?", saved.id);
            return saved;
        });
        if (account.role != role) throw new IllegalStateException("Bootstrap email already has a different role.");
        return account;
    }

    private void addSkill(long agent, String code) {
        db.update("""
                INSERT INTO agent_categories(agent_id,category_id,proficiency) VALUES (?,?,3)
                ON DUPLICATE KEY UPDATE proficiency=agent_categories.proficiency
                """, agent, tickets.category(code));
    }
}