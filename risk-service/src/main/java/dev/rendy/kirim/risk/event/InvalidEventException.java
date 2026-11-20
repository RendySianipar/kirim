package dev.rendy.kirim.risk.event;

/** A message that will never be processable. It goes straight to the dead-letter topic, without retries. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
