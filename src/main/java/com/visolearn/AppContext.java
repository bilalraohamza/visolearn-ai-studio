package com.visolearn;

import java.util.concurrent.CompletableFuture;

/**
 * Application context that holds shared, lifecycle-bound resources.
 *
 * <p>An {@code AppContext} instance is created once in {@link MainApp#start}
 * and injected explicitly into every controller that needs it via
 * {@link javafx.fxml.FXMLLoader#setControllerFactory}. This replaces the
 * previous pattern of controllers calling static methods on {@code MainApp}
 * to obtain the shared classifier.</p>
 *
 * <h3>Why this matters</h3>
 * <ul>
 *   <li>No class outside {@code MainApp} can reach the classifier without
 *       being given an {@code AppContext} — accidental global access is
 *       impossible.</li>
 *   <li>Controllers that receive an {@code AppContext} can be constructed in
 *       unit tests with a pre-completed or deliberately-failed future,
 *       enabling tests without model files or a JavaFX runtime.</li>
 *   <li>New shared resources (e.g., a settings bus, audit logger) can be
 *       added as fields here without touching {@code MainApp}'s public API.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@code MainApp.start()} constructs {@code new AppContext()}.</li>
 *   <li>The model-init background thread calls {@link #completeClassifier}
 *       on success or {@link #failClassifier} on failure.</li>
 *   <li>Controllers subscribe via {@link #getClassifierFuture()} in their
 *       {@code initialize()} method — they are notified the instant the
 *       model finishes loading.</li>
 * </ol>
 */
public final class AppContext {

    /**
     * Resolved exactly once: with the ready classifier on success,
     * or exceptionally on failure.
     */
    private final CompletableFuture<SkinClassifier> classifierFuture =
            new CompletableFuture<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Producer API — called only by MainApp
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Signals that the shared classifier is ready.
     * Called by {@link MainApp} on the model-init background thread after
     * {@link SkinClassifier#initialize()} returns successfully.
     *
     * @param classifier The fully initialised classifier. Must not be {@code null}.
     * @throws NullPointerException if {@code classifier} is {@code null}.
     */
    public void completeClassifier(SkinClassifier classifier) {
        if (classifier == null) throw new NullPointerException("classifier must not be null");
        classifierFuture.complete(classifier);
    }

    /**
     * Signals that classifier initialisation failed.
     * Called by {@link MainApp} on the model-init background thread when
     * {@link SkinClassifier#initialize()} throws.
     *
     * @param cause The exception that caused the failure. Must not be {@code null}.
     */
    public void failClassifier(Throwable cause) {
        if (cause == null) cause = new RuntimeException("Model init failed (unknown cause)");
        classifierFuture.completeExceptionally(cause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer API — called by controllers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the {@link CompletableFuture} that resolves when the shared
     * {@link SkinClassifier} is ready (or fails).
     *
     * <p>Controllers should call this in {@code initialize()} and attach a
     * {@link CompletableFuture#whenComplete} callback to wire up the classifier
     * reference and unlock the UI — exactly as before, but without going through
     * a global static method.</p>
     *
     * @return The classifier future; never {@code null}, never replaced.
     */
    public CompletableFuture<SkinClassifier> getClassifierFuture() {
        return classifierFuture;
    }
}
