package brainjar.recall.store;

import java.sql.*;
import java.time.Instant;

public class SqliteChatHistoryStore implements AutoCloseable {

    private final Connection connection;

    public SqliteChatHistoryStore(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            createTable();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise chat history store", e);
        }
    }

    public String getMessagesJson(String memoryId) {
        var sql = "SELECT messages FROM chat_history WHERE memory_id = ?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, memoryId);
            var rs = stmt.executeQuery();
            return rs.next() ? rs.getString("messages") : null;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read chat history for " + memoryId, e);
        }
    }

    public void updateMessagesJson(String memoryId, String messagesJson) {
        var sql = "INSERT INTO chat_history (memory_id, messages, updated_at) VALUES (?, ?, ?) "
                + "ON CONFLICT(memory_id) DO UPDATE SET messages = excluded.messages, updated_at = excluded.updated_at";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, memoryId);
            stmt.setString(2, messagesJson);
            stmt.setString(3, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update chat history for " + memoryId, e);
        }
    }

    public void deleteMessages(String memoryId) {
        var sql = "DELETE FROM chat_history WHERE memory_id = ?";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, memoryId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete chat history for " + memoryId, e);
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close chat history store", e);
        }
    }

    private void createTable() throws SQLException {
        try (var stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_history (
                        memory_id TEXT PRIMARY KEY,
                        messages TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )""");
        }
    }
}
