package brainjar.recall.kg;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KnowledgeGraph implements AutoCloseable {

    static final String LEGACY_EXTRACTOR_VERSION = "mentions-v0";

    private final Connection connection;

    public KnowledgeGraph(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            createTables();
            migrate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize knowledge graph", e);
        }
    }

    public String addTriple(String subjectName, String predicate, String objectName,
                            LocalDate validFrom, double confidence, String sourcePageId) {
        return addTriple(subjectName, predicate, objectName, validFrom, confidence, sourcePageId,
                LEGACY_EXTRACTOR_VERSION);
    }

    public String addTriple(String subjectName, String predicate, String objectName,
                            LocalDate validFrom, double confidence, String sourcePageId,
                            String extractorVersion) {
        var subjectId = upsertEntity(subjectName, "unknown");
        var objectId = upsertEntity(objectName, "unknown");
        var normalizedPredicate = Predicate.normalize(predicate);

        var existing = findOpenTriple(subjectId, normalizedPredicate, objectId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var tripleId = generateTripleId(subjectId, normalizedPredicate, objectId);
        var sql = "INSERT INTO triples (id, subject, predicate, object, valid_from, confidence, source_page_id, extractor_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        executeUpdate(sql, tripleId, subjectId, normalizedPredicate, objectId,
                validFrom != null ? validFrom.toString() : null, confidence, sourcePageId,
                extractorVersion);
        return tripleId;
    }

    /**
     * Transactional upsert of every triple attributed to a single page.
     *
     * <ol>
     *   <li>Short-circuits when the {@code extraction_state} row already
     *       matches {@code (extractorVersion, contentHash)} — re-running the
     *       same extractor on unchanged content is a no-op.</li>
     *   <li>Closes all triples for this page emitted by older extractor
     *       versions (so stale mined triples don't linger).</li>
     *   <li>For each incoming triple whose predicate is
     *       {@link Predicate.Kind#FUNCTIONAL}, closes any open triple with
     *       the same {@code (subject, predicate)} but a different object,
     *       regardless of which page it came from. That's supersession:
     *       "Sara works_at Acme" → "Sara works_at Globex" retires Acme.</li>
     *   <li>Inserts the incoming triples (deduped against already-open ones).</li>
     *   <li>Upserts the {@code extraction_state} row.</li>
     * </ol>
     */
    public UpsertOutcome upsertWithSupersession(String pageId,
                                                String extractorVersion,
                                                String contentHash,
                                                List<Triple> triples) {
        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("pageId must not be blank");
        }
        if (extractorVersion == null || extractorVersion.isBlank()) {
            throw new IllegalArgumentException("extractorVersion must not be blank");
        }

        try {
            connection.setAutoCommit(false);

            if (matchesExtractionState(pageId, extractorVersion, contentHash)) {
                connection.commit();
                return new UpsertOutcome(0, 0, 0, true);
            }

            int closedStale = closeStaleTriples(pageId, extractorVersion);

            int inserted = 0;
            int superseded = 0;
            for (var incoming : triples == null ? List.<Triple>of() : triples) {
                var subjectId = upsertEntity(incoming.subject(), "unknown");
                var objectId = upsertEntity(incoming.object(), "unknown");
                var normalizedPredicate = Predicate.normalize(incoming.predicate());
                var validFrom = incoming.validFrom() != null ? incoming.validFrom() : LocalDate.now();

                if (Predicate.isFunctional(normalizedPredicate)) {
                    superseded += supersedeFunctional(subjectId, normalizedPredicate, objectId, validFrom);
                }

                var existing = findOpenTriple(subjectId, normalizedPredicate, objectId);
                if (existing.isPresent()) {
                    continue;
                }

                var tripleId = generateTripleId(subjectId, normalizedPredicate, objectId);
                var sql = "INSERT INTO triples (id, subject, predicate, object, valid_from, confidence, source_page_id, extractor_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                executeUpdate(sql, tripleId, subjectId, normalizedPredicate, objectId,
                        validFrom.toString(), incoming.confidence(), pageId, extractorVersion);
                inserted++;
            }

            upsertExtractionState(pageId, extractorVersion, contentHash);

            connection.commit();
            return new UpsertOutcome(inserted, closedStale, superseded, false);
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("Failed to upsert triples for page " + pageId, e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public void invalidate(String subjectName, String predicate, String objectName, LocalDate endDate) {
        var subjectId = Entity.normalizeId(subjectName);
        var objectId = Entity.normalizeId(objectName);
        var normalizedPredicate = Predicate.normalize(predicate);

        var sql = "UPDATE triples SET valid_to = ? WHERE subject = ? AND predicate = ? AND object = ? AND valid_to IS NULL";
        executeUpdate(sql, endDate.toString(), subjectId, normalizedPredicate, objectId);
    }

    /**
     * Close all currently-open triples whose {@code source_page_id} matches the
     * given page id, by setting their {@code valid_to} to {@code endDate}. Used
     * when a memory page is retracted via forget — we don't delete history, we
     * mark it as no longer valid. Also drops the page's {@code extraction_state}
     * row so a future remine doesn't short-circuit.
     */
    public int invalidateByPageId(String pageId, LocalDate endDate) {
        if (pageId == null) {
            return 0;
        }
        int closed;
        var sql = "UPDATE triples SET valid_to = ? WHERE source_page_id = ? AND valid_to IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, endDate.toString());
            stmt.setString(2, pageId);
            closed = stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to invalidate by page id", e);
        }
        executeUpdate("DELETE FROM extraction_state WHERE page_id = ?", pageId);
        return closed;
    }

    public List<Triple> query(String subjectName) {
        var subjectId = Entity.normalizeId(subjectName);
        return queryTriples("SELECT * FROM triples WHERE subject = ?", subjectId);
    }

    public List<Triple> queryAsOf(String subjectName, LocalDate date) {
        var subjectId = Entity.normalizeId(subjectName);
        var dateStr = date.toString();
        return queryTriples(
                "SELECT * FROM triples WHERE subject = ? AND (valid_from IS NULL OR valid_from <= ?) AND (valid_to IS NULL OR valid_to >= ?)",
                subjectId, dateStr, dateStr
        );
    }

    /**
     * Currently-open triples involving {@code entityName} on either side.
     * Canonical-id resolution is applied: if the given entity is an alias
     * pointing at a canonical entity, its canonical neighbours are returned.
     */
    public List<Triple> neighbors(String entityName) {
        var canonicalId = canonicalIdFor(entityName);
        return queryTriples(
                "SELECT * FROM triples WHERE (subject = ? OR object = ?) AND valid_to IS NULL ORDER BY valid_from DESC",
                canonicalId, canonicalId
        );
    }

    /**
     * All triples (open and closed) for an entity, optionally filtered by
     * predicate. Ordered by {@code valid_from} so callers can read the
     * sequence of changes.
     */
    public List<Triple> history(String entityName, String predicate) {
        var canonicalId = canonicalIdFor(entityName);
        if (predicate == null || predicate.isBlank()) {
            return queryTriples(
                    "SELECT * FROM triples WHERE subject = ? OR object = ? ORDER BY valid_from",
                    canonicalId, canonicalId
            );
        }
        var normalizedPredicate = Predicate.normalize(predicate);
        return queryTriples(
                "SELECT * FROM triples WHERE (subject = ? OR object = ?) AND predicate = ? ORDER BY valid_from",
                canonicalId, canonicalId, normalizedPredicate
        );
    }

    /**
     * Look up a single triple by id. {@code graphExplain} on the tool layer
     * joins the result with {@code PageStore} to surface the source page.
     */
    public Optional<Triple> findTriple(String tripleId) {
        if (tripleId == null) {
            return Optional.empty();
        }
        var rows = queryTriples("SELECT * FROM triples WHERE id = ?", tripleId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Triple> allTriples() {
        return queryTriples("SELECT * FROM triples ORDER BY subject, predicate, object");
    }

    public List<Triple> openTriples() {
        return queryTriples(
                "SELECT * FROM triples WHERE valid_to IS NULL ORDER BY subject, predicate, object");
    }

    /**
     * Close every open triple whose {@code source_page_id} refers to a
     * page that no longer exists in {@link brainjar.recall.store.PageStore}.
     * Safe to re-run: it only touches {@code valid_to IS NULL} rows. Used
     * by the remine flow to tidy up after pages have been forgotten while
     * the orphan triples lingered.
     *
     * @param isPageMissing predicate returning {@code true} when the given
     *                      page id is no longer in the store.
     * @return number of triples closed.
     */
    public int sweepOrphans(java.util.function.Predicate<String> isPageMissing) {
        var orphanPageIds = new ArrayList<String>();
        try (var stmt = connection.prepareStatement(
                "SELECT DISTINCT source_page_id FROM triples "
                        + "WHERE source_page_id IS NOT NULL AND valid_to IS NULL");
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                var pageId = rs.getString(1);
                if (pageId != null && isPageMissing.test(pageId)) {
                    orphanPageIds.add(pageId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to scan for orphan triples", e);
        }
        int closed = 0;
        for (var pageId : orphanPageIds) {
            closed += invalidateByPageId(pageId, LocalDate.now());
        }
        return closed;
    }

    public List<Entity> allEntities() {
        var entities = new ArrayList<Entity>();
        try (var stmt = connection.prepareStatement("SELECT * FROM entities ORDER BY name");
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                entities.add(mapEntity(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query entities", e);
        }
        return List.copyOf(entities);
    }

    /**
     * The stored {@code (extractor_version, content_hash)} for a page, or
     * empty if we've never extracted it.
     */
    public Optional<ExtractionState> extractionStateFor(String pageId) {
        if (pageId == null) {
            return Optional.empty();
        }
        try (var stmt = connection.prepareStatement(
                "SELECT page_id, extractor_version, content_hash, extracted_at FROM extraction_state WHERE page_id = ?")) {
            stmt.setString(1, pageId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new ExtractionState(
                    rs.getString("page_id"),
                    rs.getString("extractor_version"),
                    rs.getString("content_hash"),
                    Instant.parse(rs.getString("extracted_at"))
            ));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read extraction_state", e);
        }
    }

    /**
     * Every page id that the {@code extraction_state} table has ever seen.
     * Handy on startup for figuring out which pages need re-extracting at a
     * newer extractor version.
     */
    public List<ExtractionState> allExtractionStates() {
        var states = new ArrayList<ExtractionState>();
        try (var stmt = connection.prepareStatement(
                "SELECT page_id, extractor_version, content_hash, extracted_at FROM extraction_state");
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                states.add(new ExtractionState(
                        rs.getString("page_id"),
                        rs.getString("extractor_version"),
                        rs.getString("content_hash"),
                        Instant.parse(rs.getString("extracted_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list extraction_state", e);
        }
        return List.copyOf(states);
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close connection", e);
        }
    }

    private void createTables() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS entities (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        type TEXT,
                        created_at TEXT NOT NULL,
                        canonical_id TEXT
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS triples (
                        id TEXT PRIMARY KEY,
                        subject TEXT NOT NULL,
                        predicate TEXT NOT NULL,
                        object TEXT NOT NULL,
                        valid_from TEXT,
                        valid_to TEXT,
                        confidence REAL DEFAULT 1.0,
                        source_page_id TEXT,
                        extractor_version TEXT,
                        FOREIGN KEY (subject) REFERENCES entities(id),
                        FOREIGN KEY (object) REFERENCES entities(id)
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS extraction_state (
                        page_id TEXT PRIMARY KEY,
                        extractor_version TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        extracted_at TEXT NOT NULL
                    )""");
        }
    }

    /**
     * Additive migrations for older databases that pre-date the
     * {@code extractor_version} and {@code canonical_id} columns.
     */
    private void migrate() throws SQLException {
        if (!columnExists("triples", "extractor_version")) {
            try (var stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE triples ADD COLUMN extractor_version TEXT");
                stmt.execute("UPDATE triples SET extractor_version = '" + LEGACY_EXTRACTOR_VERSION
                        + "' WHERE extractor_version IS NULL");
            }
        }
        if (!columnExists("entities", "canonical_id")) {
            try (var stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE entities ADD COLUMN canonical_id TEXT");
            }
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (var stmt = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String upsertEntity(String name, String type) {
        var id = Entity.normalizeId(name);
        var sql = "INSERT OR IGNORE INTO entities (id, name, type, created_at, canonical_id) VALUES (?, ?, ?, ?, ?)";
        executeUpdate(sql, id, name, type, Instant.now().toString(), id);
        return id;
    }

    private Optional<String> findOpenTriple(String subjectId, String predicate, String objectId) {
        var sql = "SELECT id FROM triples WHERE subject = ? AND predicate = ? AND object = ? AND valid_to IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, subjectId);
            stmt.setString(2, predicate);
            stmt.setString(3, objectId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(rs.getString("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find open triple", e);
        }
        return Optional.empty();
    }

    private int closeStaleTriples(String pageId, String currentVersion) throws SQLException {
        var sql = "UPDATE triples SET valid_to = ? "
                + "WHERE source_page_id = ? AND valid_to IS NULL "
                + "AND (extractor_version IS NULL OR extractor_version != ?)";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, LocalDate.now().toString());
            stmt.setString(2, pageId);
            stmt.setString(3, currentVersion);
            return stmt.executeUpdate();
        }
    }

    private int supersedeFunctional(String subjectId, String normalizedPredicate,
                                    String newObjectId, LocalDate validFrom) throws SQLException {
        var sql = "UPDATE triples SET valid_to = ? "
                + "WHERE subject = ? AND predicate = ? AND object != ? AND valid_to IS NULL";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, validFrom.toString());
            stmt.setString(2, subjectId);
            stmt.setString(3, normalizedPredicate);
            stmt.setString(4, newObjectId);
            return stmt.executeUpdate();
        }
    }

    private boolean matchesExtractionState(String pageId, String extractorVersion, String contentHash)
            throws SQLException {
        try (var stmt = connection.prepareStatement(
                "SELECT extractor_version, content_hash FROM extraction_state WHERE page_id = ?")) {
            stmt.setString(1, pageId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return false;
            }
            var storedVersion = rs.getString("extractor_version");
            var storedHash = rs.getString("content_hash");
            return extractorVersion.equals(storedVersion)
                    && contentHash != null
                    && contentHash.equals(storedHash);
        }
    }

    private void upsertExtractionState(String pageId, String extractorVersion, String contentHash)
            throws SQLException {
        var sql = "INSERT INTO extraction_state (page_id, extractor_version, content_hash, extracted_at) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(page_id) DO UPDATE SET "
                + "extractor_version = excluded.extractor_version, "
                + "content_hash = excluded.content_hash, "
                + "extracted_at = excluded.extracted_at";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, pageId);
            stmt.setString(2, extractorVersion);
            stmt.setString(3, contentHash == null ? "" : contentHash);
            stmt.setString(4, Instant.now().toString());
            stmt.executeUpdate();
        }
    }

    private String canonicalIdFor(String entityName) {
        var normalized = Entity.normalizeId(entityName);
        try (var stmt = connection.prepareStatement(
                "SELECT canonical_id FROM entities WHERE id = ?")) {
            stmt.setString(1, normalized);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var canonical = rs.getString("canonical_id");
                if (canonical != null && !canonical.isBlank()) {
                    return canonical;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve canonical id for " + entityName, e);
        }
        return normalized;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private String generateTripleId(String subjectId, String predicate, String objectId) {
        var base = "t_%s_%s_%s".formatted(subjectId, predicate, objectId);
        return base.length() > 64 ? base.substring(0, 64) : base;
    }

    private List<Triple> queryTriples(String sql, String... params) {
        var triples = new ArrayList<Triple>();
        try (var stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setString(i + 1, params[i]);
            }
            var rs = stmt.executeQuery();
            while (rs.next()) {
                triples.add(mapTriple(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query triples", e);
        }
        return List.copyOf(triples);
    }

    private void executeUpdate(String sql, Object... params) {
        try (var stmt = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    stmt.setNull(i + 1, Types.VARCHAR);
                } else if (params[i] instanceof Double d) {
                    stmt.setDouble(i + 1, d);
                } else {
                    stmt.setString(i + 1, params[i].toString());
                }
            }
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute update", e);
        }
    }

    private Triple mapTriple(ResultSet rs) throws SQLException {
        return new Triple(
                rs.getString("id"),
                rs.getString("subject"),
                rs.getString("predicate"),
                rs.getString("object"),
                parseDate(rs.getString("valid_from")),
                parseDate(rs.getString("valid_to")),
                rs.getDouble("confidence"),
                rs.getString("source_page_id")
        );
    }

    private Entity mapEntity(ResultSet rs) throws SQLException {
        return new Entity(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("type"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    private LocalDate parseDate(String value) {
        return value != null ? LocalDate.parse(value) : null;
    }

    /**
     * Summary of a single {@link #upsertWithSupersession} call. Useful for
     * logging and for tests that want to check supersession actually fired.
     */
    public record UpsertOutcome(int inserted, int closedStale, int superseded, boolean shortCircuited) {}

    public record ExtractionState(String pageId, String extractorVersion, String contentHash, Instant extractedAt) {}
}
