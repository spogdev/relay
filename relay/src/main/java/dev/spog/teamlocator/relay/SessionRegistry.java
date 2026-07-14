package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live connections. Every open socket maps to a {@link Session}; once authenticated, a
 * session is also indexed by verified UUID and grouped by MC-server scope so routing can restrict
 * itself to peers actually in the same world.
 */
public final class SessionRegistry {
    private final Gson gson = new Gson();

    private final Map<WebSocket, Session> byConn = new ConcurrentHashMap<>();
    private final Map<UUID, Session> byUuid = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Session>> byScope = new ConcurrentHashMap<>();

    public Session open(WebSocket conn) {
        Session s = new Session(conn);
        byConn.put(conn, s);
        return s;
    }

    public Session get(WebSocket conn) {
        return byConn.get(conn);
    }

    /**
     * Promote a freshly-authenticated session into the UUID and scope indexes. If the same account
     * is already connected (e.g. a stale socket), the old session is closed so a verified UUID maps
     * to exactly one live connection.
     */
    public void register(Session session) {
        UUID uuid = session.uuid();
        Session prior = byUuid.put(uuid, session);
        if (prior != null && prior != session) {
            removeFromScope(prior);
            prior.conn().close();
            byConn.remove(prior.conn());
        }
        byScope.computeIfAbsent(session.scope(), k -> new ConcurrentHashMap<>())
                .put(uuid, session);
    }

    public void close(WebSocket conn) {
        Session s = byConn.remove(conn);
        if (s == null) {
            return;
        }
        if (s.authenticated()) {
            byUuid.remove(s.uuid(), s);
            removeFromScope(s);
        }
    }

    private void removeFromScope(Session s) {
        Map<UUID, Session> scope = byScope.get(s.scope());
        if (scope != null) {
            scope.remove(s.uuid(), s);
            if (scope.isEmpty()) {
                byScope.remove(s.scope(), scope);
            }
        }
    }

    /** Authenticated sessions sharing the given MC-server scope. Empty if none. */
    public Collection<Session> sessionsInScope(String scope) {
        Map<UUID, Session> m = byScope.get(scope);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    /** Serialize a protocol message to JSON and send it over the session's socket. */
    public void send(Session session, Object message) {
        WebSocket conn = session.conn();
        if (conn != null && conn.isOpen()) {
            conn.send(gson.toJson(message));
        }
    }
}
