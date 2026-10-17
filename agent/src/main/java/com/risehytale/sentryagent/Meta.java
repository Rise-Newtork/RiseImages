package com.risehytale.sentryagent;

import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/** The parts of {@code life.ContainerMeta} (protobuf JSON, as the creator writes it) this agent reads. */
record Meta(String dsn, String environment, String release, String logsLevel, String container, String node,
            List<String> serverTypes, String hubType) {

    // org.bson because the server jar already carries it; this agent ships no libraries of its own.
    static Meta parse(String json) {
        BsonDocument meta = BsonDocument.parse(json);
        BsonDocument sentry = meta.getDocument("sentry", new BsonDocument());
        return new Meta(
                text(sentry, "dsn"),
                text(sentry, "environment"),
                text(sentry, "release"),
                text(sentry, "logsLevel"),
                text(meta, "containerName"),
                text(meta.getDocument("nodeMeta", new BsonDocument()), "nodeId"),
                meta.getArray("serverTypes", new org.bson.BsonArray()).stream()
                        .filter(BsonValue::isString).map(v -> v.asString().getValue()).toList(),
                text(meta.getDocument("hub", new BsonDocument()), "type"));
    }

    private static String text(BsonDocument document, String key) {
        BsonValue value = document.get(key);
        return value != null && value.isString() ? value.asString().getValue().trim() : "";
    }
}
