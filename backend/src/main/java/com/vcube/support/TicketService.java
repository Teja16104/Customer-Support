package com.vcube.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static com.vcube.support.Account.Role.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TicketService {
    enum Status { OPEN, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED }

    enum Priority {
        LOW(1440, 5760), MEDIUM(480, 2880), HIGH(120, 720), URGENT(30, 240);

        final int replyMinutes;
        final int resolutionMinutes;

        Priority(int replyMinutes, int resolutionMinutes) {
            this.replyMinutes = replyMinutes;
            this.resolutionMinutes = resolutionMinutes;
        }
    }

    static final String ACTIVE = "('OPEN','IN_PROGRESS','WAITING_CUSTOMER')";
    private static final Set<String> ACTIVE_STATES = Set.of("OPEN", "IN_PROGRESS", "WAITING_CUSTOMER");
    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.OPEN, Set.of(Status.IN_PROGRESS),
            Status.IN_PROGRESS, Set.of(Status.RESOLVED, Status.WAITING_CUSTOMER),
            Status.WAITING_CUSTOMER, Set.of(Status.IN_PROGRESS, Status.RESOLVED),
            Status.RESOLVED, Set.of(Status.IN_PROGRESS, Status.CLOSED),
            Status.CLOSED, Set.of());

    private static final String SELECT = """
            SELECT t.*, c.code, c.name, c.description AS category_description,
                   a.agent_id, u.display_name AS agent_name, cu.display_name AS customer_name,
                   EXISTS(SELECT 1 FROM ticket_feedback f WHERE f.ticket_id=t.id) AS has_feedback
            FROM tickets t JOIN categories c ON c.id=t.category_id JOIN users cu ON cu.id=t.customer_id
            LEFT JOIN ticket_assignments a ON a.active_ticket_id=t.id
            LEFT JOIN users u ON u.id=a.agent_id
            """;

    private final JdbcTemplate db;
    private final AccountRepository accounts;
    private final ObjectMapper json;

    TicketService(JdbcTemplate db, AccountRepository accounts, ObjectMapper json) {
        this.db = db;
        this.accounts = accounts;
        this.json = json;
    }

    Account actor(Authentication principal, Account.Role... roles) {
        Account account = accounts.findByEmail(principal.getName()).filter(user -> user.enabled)
                .orElseThrow(() -> fail(401, "Sign in again."));
        if (roles.length > 0 && !Arrays.asList(roles).contains(account.role)) {
            throw fail(403, "This action requires a different role.");
        }
        return account;
    }

    record Created(Map<String, Object> ticket, boolean replay) {}

    @Transactional
    public Created submit(Account actor, UUID requestKey, String subject, String description) {
        subject = subject.strip();
        description = description.strip();
        if (subject.length() < 3 || description.length() < 10) {
            throw fail(400, "Enter a subject and description.");
        }

        byte[] key = binary(requestKey);
        byte[] hash = digest(write(List.of(subject, description)));
        one("SELECT id FROM users WHERE id=? FOR UPDATE", actor.id);

        var previous = db.queryForList(
                "SELECT id,submission_hash FROM tickets WHERE customer_id=? AND submission_key=?",
                actor.id, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!MessageDigest.isEqual(hash, (byte[]) row.get("submission_hash"))) {
                throw fail(409, "This key was already used for a different complaint.");
            }
            return new Created(view(raw(number(row.get("id"))), actor, true), true);
        }

        var bits = new java.math.BigInteger(1, binary(UUID.randomUUID()));
        char[] encoded = new char[26];
        for (int i = 25; i >= 0; i--) {
            encoded[i] = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".charAt(bits.intValue() & 31);
            bits = bits.shiftRight(5);
        }
        String ticketKey = "TKT-" + new String(encoded);
        Instant now = Instant.now();
        db.update("""
                INSERT INTO tickets(ticket_key,customer_id,category_id,submission_key,submission_hash,subject,description,
                    sla_started_at,first_response_due_at,resolution_due_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, ticketKey, actor.id, category("UNCATEGORIZED"), key, hash, subject, description,
                ts(now), ts(now.plusSeconds(480 * 60)), ts(now.plusSeconds(2880 * 60)));

        var row = scoped(actor, ticketKey, false);
        long id = number(row.get("id"));
        event(id, actor.id, 1, "CREATED", null, "OPEN", null);
        db.update("INSERT INTO ai_jobs(ticket_id) VALUES (?)", id);
        return new Created(view(row, actor, true), false);
    }

    public Map<String, Object> list(Account actor, Status status, Priority priority, String code,
                                    String scope, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw fail(400, "Invalid page or size.");
        }
        var args = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (actor.role == CUSTOMER) {
            where += " AND t.customer_id=?";
            args.add(actor.id);
        }
        if (actor.role == AGENT) {
            where += " AND a.agent_id=?";
            args.add(actor.id);
        }
        if ("UNASSIGNED".equals(scope) && actor.role == ADMIN) {
            where += " AND a.id IS NULL AND t.status IN " + ACTIVE;
        }
        if (status != null) {
            where += " AND t.status=?";
            args.add(status.name());
        }
        if (priority != null) {
            where += " AND t.priority=?";
            args.add(priority.name());
        }
        if (code != null) {
            where += " AND c.code=?";
            args.add(code);
        }
        long total = db.queryForObject("SELECT COUNT(*) FROM (" + SELECT + where + ") matches",
                Long.class, args.toArray());
        args.add(size);
        args.add(page * size);
        var rows = db.queryForList(SELECT + where + " ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",
                args.toArray());
        return fields("items", rows.stream().map(row -> view(row, actor, false)).toList(),
                "page", page, "size", size, "total", total);
    }

    public Map<String, Object> get(Account actor, String key) {
        return view(scoped(actor, key, false), actor, true);
    }

    @Transactional
    public Map<String, Object> status(Account actor, String key, String tag, Status next, String summary) {
        var row = edit(actor, key, tag);
        long id = number(row.get("id"));
        int cycle = (int) number(row.get("sla_cycle"));
        Status previous = Status.valueOf(row.get("status").toString());
        if (!TRANSITIONS.get(previous).contains(next)) throw fail(409, "This status transition is unavailable.");
        if (actor.role == CUSTOMER && next != Status.CLOSED
                && !(previous == Status.WAITING_CUSTOMER && next == Status.IN_PROGRESS)) {
            throw fail(403, "Staff must change this status.");
        }
        if (next == Status.IN_PROGRESS && row.get("agent_id") == null) {
            throw fail(409, "Assign an agent before starting work.");
        }

        Instant now = Instant.now();
        Map<String, Object> details = null;
        if (previous == Status.RESOLVED && next == Status.IN_PROGRESS) {
            long agent = number(row.get("agent_id"));
            lockAgent(agent);
            checkCapacity(agent);
            cycle++;
            Priority priority = Priority.valueOf(row.get("priority").toString());
            db.update("""
                    UPDATE tickets SET status='IN_PROGRESS',sla_cycle=?,sla_started_at=?,first_response_at=NULL,
                        resolved_at=NULL,closed_at=NULL,resolution_summary=NULL,
                        first_response_due_at=?,resolution_due_at=? WHERE id=?
                    """, cycle, ts(now), ts(now.plusSeconds(priority.replyMinutes * 60L)),
                    ts(now.plusSeconds(priority.resolutionMinutes * 60L)), id);
        } else if (next == Status.RESOLVED) {
            if (summary == null || summary.isBlank()) throw fail(400, "Enter a resolution summary.");
            if (row.get("first_response_at") == null) throw fail(409, "Post a public staff reply first.");
            if (row.get("agent_id") != null) lockAgent(number(row.get("agent_id")));
            details = outcome(row, "resolution_due_at", now);
            db.update("UPDATE tickets SET status='RESOLVED', resolved_at=?, resolution_summary=? WHERE id=?",
                    ts(now), summary.strip(), id);
        } else if (next == Status.CLOSED) {
            db.update("UPDATE tickets SET status='CLOSED',closed_at=? WHERE id=?", ts(now), id);
        } else {
            db.update("UPDATE tickets SET status=? WHERE id=?", next.name(), id);
        }

        bump(id);
        event(id, actor.id, cycle, "STATUS_CHANGED", previous.name(), next.name(), details);
        return view(raw(id), actor, true);
    }

    @Transactional
    public Map<String, Object> triage(Account actor, String key, String tag, String code, Priority priority, String reason) {
        var row = edit(actor, key, tag);
        active(row);
        long id = number(row.get("id"));
        int cycle = (int) number(row.get("sla_cycle"));
        if (code == null && priority == null) throw fail(400, "Provide a category or priority.");
        if (code != null) {
            db.update("UPDATE tickets SET category_id=?,category_overridden=TRUE WHERE id=?", category(code), id);
            event(id, actor.id, cycle, "CATEGORY_CHANGED", null, null,
                    fields("from", row.get("code"), "to", code, "reason", reason));
        }
        if (priority != null) {
            deadlines(id, priority, instant(row.get("sla_started_at")), true);
            event(id, actor.id, cycle, "PRIORITY_CHANGED", null, null,
                    fields("from", row.get("priority"), "to", priority.name(), "reason", reason));
        }
        bump(id);
        return view(raw(id), actor, true);
    }

    @Transactional
    public Map<String, Object> assign(Account actor, String key, String tag, Long agent, String reason) {
        var row = edit(actor, key, tag);
        active(row);
        Long old = row.get("agent_id") == null ? null : number(row.get("agent_id"));
        if (Objects.equals(old, agent)) return view(row, actor, true);
        if (agent == null && !row.get("status").equals("OPEN")) {
            throw fail(409, "Only open tickets can be unassigned.");
        }
        var locks = new TreeSet<Long>();
        if (old != null) locks.add(old);
        if (agent != null) locks.add(agent);
        locks.forEach(this::lockAgent);
        if (agent != null) checkCapacity(agent);
        recordAssignment(row, agent, actor.id, reason);
        bump(number(row.get("id")));
        return view(raw(number(row.get("id"))), actor, true);
    }

    List<Map<String, Object>> routing(long categoryId) {
        return db.queryForList("""
                SELECT u.id, u.display_name, u.max_active_tickets, COALESCE(s.proficiency,0) proficiency,
                    COUNT(t.id) active_count
                FROM users u LEFT JOIN agent_categories s ON s.agent_id=u.id AND s.category_id=?
                LEFT JOIN ticket_assignments a ON a.agent_id=u.id AND a.ended_at IS NULL
                LEFT JOIN tickets t ON t.id=a.ticket_id AND t.status IN %s
                WHERE u.enabled AND u.role='AGENT' AND u.routing_available
                GROUP BY u.id,u.display_name,u.max_active_tickets,s.proficiency
                HAVING active_count<u.max_active_tickets
                ORDER BY (proficiency>0) DESC, active_count/u.max_active_tickets, proficiency DESC,u.id LIMIT 5
                """.formatted(ACTIVE), categoryId);
    }

    void autoAssign(Map<String, Object> row) {
        if (row.get("agent_id") != null) return;
        var candidates = routing(number(row.get("category_id")));
        if (candidates.isEmpty()) return;

        long agent = number(candidates.getFirst().get("id"));
        lockAgent(agent);
        var profile = one("SELECT routing_available,enabled,max_active_tickets FROM users WHERE id=?", agent);
        if (truth(profile.get("enabled")) && truth(profile.get("routing_available"))
                && load(agent) < number(profile.get("max_active_tickets"))) {
            recordAssignment(row, agent, null, "Category skill and available workload");
        }
    }

    private void recordAssignment(Map<String, Object> row, Long agent, Long assignedBy, String reason) {
        long id = number(row.get("id"));
        db.update("UPDATE ticket_assignments SET ended_at=UTC_TIMESTAMP(6) WHERE active_ticket_id=?", id);
        if (agent != null) {
            db.update("INSERT INTO ticket_assignments(ticket_id,agent_id,assigned_by,source,reason) VALUES (?,?,?,?,?)",
                    id, agent, assignedBy, assignedBy == null ? "AUTO" : "MANUAL", reason);
        }
        event(id, assignedBy, (int) number(row.get("sla_cycle")), "ASSIGNMENT_CHANGED", null, null,
                fields("agentId", agent, "reason", reason));
    }

    public List<Map<String, Object>> comments(Account actor, String key) {
        long id = number(scoped(actor, key, false).get("id"));
        String filter = actor.role == CUSTOMER ? " AND c.visibility='PUBLIC'" : "";
        return db.queryForList("""
                SELECT c.id,c.body,c.visibility,c.created_at AS createdAt,u.display_name AS authorName
                FROM ticket_comments c JOIN users u ON u.id=c.author_id WHERE c.ticket_id=?
                """ + filter + " ORDER BY c.created_at,c.id LIMIT 100", id);
    }

    @Transactional
    public Map<String, Object> comment(Account actor, String key, String tag, String body, String visibility) {
        var row = edit(actor, key, tag);
        active(row);
        if ("INTERNAL".equals(visibility) && actor.role == CUSTOMER) {
            throw fail(403, "Only staff can add internal notes.");
        }
        long id = number(row.get("id"));
        db.update("INSERT INTO ticket_comments(ticket_id,author_id,visibility,body) VALUES (?,?,?,?)",
                id, actor.id, visibility, body.strip());
        if (actor.role != CUSTOMER && "PUBLIC".equals(visibility) && row.get("first_response_at") == null) {
            Instant now = Instant.now();
            db.update("UPDATE tickets SET first_response_at=? WHERE id=?", ts(now), id);
            event(id, actor.id, (int) number(row.get("sla_cycle")), "FIRST_RESPONSE", null, null,
                    outcome(row, "first_response_due_at", now));
        }
        bump(id);
        return view(raw(id), actor, true);
    }

    public List<Map<String, Object>> history(Account actor, String key) {
        long id = number(scoped(actor, key, false).get("id"));
        if (actor.role == CUSTOMER) {
            return db.queryForList("""
                    SELECT event_type AS type,to_status AS toStatus,created_at AS createdAt FROM ticket_events
                    WHERE ticket_id=? AND event_type IN ('CREATED','STATUS_CHANGED') ORDER BY id LIMIT 100
                    """, id);
        }
        return db.queryForList("""
                SELECT event_type AS type,from_status AS fromStatus,to_status AS toStatus,details,created_at AS createdAt
                FROM ticket_events WHERE ticket_id=? ORDER BY id LIMIT 100
                """, id);
    }

    @Transactional
    public Map<String, Object> feedback(Account actor, String key, String tag, int rating, String comment) {
        var row = edit(actor, key, tag);
        long id = number(row.get("id"));
        if (!row.get("status").equals("CLOSED")) throw fail(409, "Close the ticket before rating it.");
        if (truth(row.get("has_feedback"))) throw fail(409, "A rating already exists.");
        db.update("INSERT INTO ticket_feedback(ticket_id,rating,comment) VALUES (?,?,?)", id, rating, comment);
        bump(id);
        return view(raw(id), actor, true);
    }

    public Map<String, Object> feedback(Account actor, String key) {
        return one("SELECT rating,comment,created_at AS createdAt FROM ticket_feedback WHERE ticket_id=?",
                scoped(actor, key, false).get("id"));
    }

    public List<Map<String, Object>> similar(Account actor, String key) {
        return similarRows(actor, scoped(actor, key, false), false);
    }

    List<Map<String, Object>> similarRows(Account actor, Map<String, Object> row, boolean resolved) {
        String words = Arrays.stream((row.get("subject") + " " + row.get("description"))
                .split("[^\\p{L}\\p{N}]+")).filter(word -> word.length() >= 3).distinct().limit(30)
                .reduce((left, right) -> left + " " + right).orElse("");
        if (words.isBlank()) return List.of();

        var args = new ArrayList<>(List.of((Object) words, row.get("id")));
        String scope = "";
        if (actor.role == AGENT) {
            scope = " AND a.agent_id=?";
            args.add(actor.id);
        }
        if (resolved) {
            scope += " AND t.status IN ('RESOLVED','CLOSED') AND t.category_id=?";
            args.add(row.get("category_id"));
        }
        return db.queryForList("""
                SELECT t.ticket_key AS `key`,t.subject,t.status,c.code AS categoryCode,
                       MATCH(t.subject,t.description) AGAINST (?) AS rankScore
                """ + (resolved ? ",t.resolution_summary AS summary " : "") + """
                FROM tickets t JOIN categories c ON c.id=t.category_id
                LEFT JOIN ticket_assignments a ON a.active_ticket_id=t.id
                WHERE t.id<>? AND t.created_at>=UTC_TIMESTAMP() - INTERVAL 365 DAY
                """ + scope + " HAVING rankScore>0 ORDER BY rankScore DESC,t.id DESC LIMIT " + (resolved ? 3 : 5),
                args.toArray());
    }

    Map<String, Object> scoped(Account actor, String key, boolean lock) {
        if (lock) one("SELECT id FROM tickets WHERE ticket_key=? FOR UPDATE", key);
        var row = one(SELECT + " WHERE t.ticket_key=?", key);
        if ((actor.role == CUSTOMER && actor.id != number(row.get("customer_id")))
                || (actor.role == AGENT && (row.get("agent_id") == null || actor.id != number(row.get("agent_id"))))) {
            throw fail(404, "Ticket not found.");
        }
        return row;
    }

    Map<String, Object> raw(long id) {
        return one(SELECT + " WHERE t.id=?", id);
    }

    private Map<String, Object> edit(Account actor, String key, String tag) {
        var row = scoped(actor, key, true);
        if (tag == null) throw fail(428, "Send the latest ticket ETag in If-Match.");
        if (!etag(row).equals(tag)) throw fail(412, "Ticket changed. Fetch it again.");
        return row;
    }

    static String etag(Map<String, Object> row) {
        return "\"" + row.get("version") + "\"";
    }

    static Map<String, Object> view(Map<String, Object> row, Account actor, boolean detail) {
        var result = fields("key", row.get("ticket_key"), "subject", row.get("subject"), "status", row.get("status"),
                "priority", row.get("priority"), "categoryCode", row.get("code"), "version", row.get("version"),
                "createdAt", instant(row.get("created_at")), "updatedAt", instant(row.get("updated_at")));
        if (detail) {
            result.putAll(fields("description", row.get("description"), "feedbackSubmitted", truth(row.get("has_feedback")),
                    "category", fields("code", row.get("code"), "name", row.get("name"), "description", row.get("category_description"))));
        }
        if (actor.role != CUSTOMER) {
            result.put("staff", fields("customer", person(row.get("customer_id"), row.get("customer_name")),
                    "assignee", person(row.get("agent_id"), row.get("agent_name")), "sentiment", row.get("sentiment"),
                    "triageStatus", row.get("triage_status"), "resolutionSummary", row.get("resolution_summary"),
                    "resolutionDueAt", instant(row.get("resolution_due_at")),
                    "sla", fields("cycle", row.get("sla_cycle"), "firstResponseDueAt", instant(row.get("first_response_due_at")),
                            "resolutionDueAt", instant(row.get("resolution_due_at")))));
        }
        return result;
    }

    void lockAgent(long agent) {
        one("SELECT id FROM users WHERE id=? AND role='AGENT' FOR UPDATE", agent);
    }

    private void checkCapacity(long agent) {
        var profile = one("SELECT enabled,max_active_tickets FROM users WHERE id=?", agent);
        if (!truth(profile.get("enabled")) || load(agent) >= number(profile.get("max_active_tickets"))) {
            throw fail(409, "Agent is unavailable or at capacity.");
        }
    }

    long load(long agent) {
        return db.queryForObject("SELECT COUNT(*) FROM ticket_assignments a JOIN tickets t ON t.id=a.ticket_id "
                + "WHERE a.agent_id=? AND a.ended_at IS NULL AND t.status IN " + ACTIVE, Long.class, agent);
    }

    long category(String code) {
        var rows = db.queryForList("SELECT id FROM categories WHERE code=? AND enabled", code);
        if (rows.isEmpty()) throw fail(400, "Choose an enabled category.");
        return number(rows.getFirst().get("id"));
    }

    void deadlines(long id, Priority priority, Instant start, boolean override) {
        db.update("""
                UPDATE tickets SET priority=?,first_response_due_at=?,resolution_due_at=?,
                    priority_overridden=IF(?,TRUE,priority_overridden) WHERE id=?
                """, priority.name(), ts(start.plusSeconds(priority.replyMinutes * 60L)),
                ts(start.plusSeconds(priority.resolutionMinutes * 60L)), override, id);
    }

    void event(long id, Long actor, int cycle, String type, String from, String to, Object details) {
        db.update("""
                INSERT INTO ticket_events(ticket_id,actor_id,sla_cycle,event_type,from_status,to_status,details)
                VALUES (?,?,?,?,?,?,?)
                """, id, actor, cycle, type, from, to, details == null ? null : write(details));
    }

    void bump(long id) {
        db.update("UPDATE tickets SET version=version+1 WHERE id=?", id);
    }

    Map<String, Object> one(String sql, Object... args) {
        return db.queryForList(sql, args).stream().findFirst().orElseThrow(() -> fail(404, "Resource not found."));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }

    private static Map<String, Object> outcome(Map<String, Object> row, String due, Instant now) {
        return fields("seconds", java.time.Duration.between(instant(row.get("sla_started_at")), now).getSeconds(),
                "met", !now.isAfter(instant(row.get(due))));
    }

    static Map<String, Object> fields(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
        return map;
    }

    static Map<String, Object> person(Object id, Object name) {
        return id == null ? null : fields("id", id.toString(), "displayName", name);
    }

    static boolean activeState(Map<String, Object> row) {
        return ACTIVE_STATES.contains(row.get("status"));
    }

    static void active(Map<String, Object> row) {
        if (!activeState(row)) throw fail(409, "Ticket is inactive.");
    }

    static ResponseStatusException fail(int status, String detail) {
        return new ResponseStatusException(HttpStatus.valueOf(status), detail);
    }

    static long number(Object value) {
        return ((Number) value).longValue();
    }

    static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    static Instant instant(Object value) {
        return value instanceof LocalDateTime local ? local.toInstant(ZoneOffset.UTC) : ((Timestamp) value).toInstant();
    }

    static byte[] binary(UUID key) {
        return ByteBuffer.allocate(16).putLong(key.getMostSignificantBits()).putLong(key.getLeastSignificantBits()).array();
    }

    private static byte[] digest(String content) {
        try { return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}