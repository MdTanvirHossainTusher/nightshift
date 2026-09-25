package com.example.sync;

/**
 * Consumes shipment events off the broker.
 *
 * <p>Seeded defect #5: a payload that fails to deserialize is logged and dropped.
 * There is no dead-letter topic and no counter, so the message is gone and nobody
 * finds out. Silent data loss reads as a routine WARN in the log — the severity
 * has to come from what the code does with the failure, not from the log level.
 */
public class ShipmentEventConsumer {

    private final EventDeserializer deserializer;
    private final ShipmentHandler handler;

    public ShipmentEventConsumer(EventDeserializer deserializer, ShipmentHandler handler) {
        this.deserializer = deserializer;
        this.handler = handler;
    }

    public void onMessage(String topic, byte[] payload) {
        ShipmentEvent event;
        try {
            event = deserializer.read(payload); // NS_FRAME_DESER
        } catch (DeserializationException ex) {
            // Swallowed. No DLQ, no metric, no rethrow: the event is lost.
            return;
        }
        handler.handle(event);
    }

    public interface EventDeserializer {
        ShipmentEvent read(byte[] payload) throws DeserializationException;
    }

    public interface ShipmentHandler {
        void handle(ShipmentEvent event);
    }

    public static class DeserializationException extends Exception {
        public DeserializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record ShipmentEvent(String shipmentId, String status, long occurredAt) {
    }
}
