package com.visolearn;

import javafx.application.Platform;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lightweight controller registry that replaces the bare static
 * {@code getInstance()} singleton pattern in JavaFX controllers.
 *
 * <h3>Problem with static singletons</h3>
 * <p>Setting {@code ClassifyController.instance = this} inside
 * {@code initialize()} means:</p>
 * <ul>
 *   <li>If the FXML is reloaded (tab detach/attach, hot-reload, tests),
 *       the old reference is silently overwritten with the new controller
 *       while other holders still point to the discarded one.</li>
 *   <li>Accessing JavaFX UI nodes through these statics from a
 *       non-FX-thread causes {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h3>This solution</h3>
 * <ul>
 *   <li>Controllers register themselves via {@link #register(Object)} on
 *       {@code initialize()} — same call site as before.</li>
 *   <li>References are stored as {@link WeakReference}s: when the
 *       controller is unloaded and GC'd, the entry evaporates automatically
 *       with no memory leak and no stale-reference risk.</li>
 *   <li>Cross-controller calls go through {@link #ifPresent(Class, Consumer)},
 *       which is always null-safe and always marshals the action to the
 *       JavaFX Application Thread, preventing threading violations.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // In initialize():
 * ControllerBus.register(this);
 *
 * // Calling into another controller:
 * ControllerBus.ifPresent(HistoryController.class, HistoryController::refreshCurrentPatient);
 * ControllerBus.ifPresent(ClassifyController.class, ClassifyController::refreshPatientDropdown);
 * }</pre>
 *
 * @author Rao Hamza Bilal
 */
public final class ControllerBus {

    /**
     * Map from controller class → weak reference to the live instance.
     * {@link ConcurrentHashMap} allows registration from any thread
     * (e.g., a background init task) without locking.
     */
    private static final ConcurrentHashMap<Class<?>, WeakReference<?>> registry =
            new ConcurrentHashMap<>();

    /** Utility class — no instances. */
    private ControllerBus() {}

    /**
     * Registers a controller instance, keyed by its exact runtime class.
     *
     * <p>Call this as the very first line of {@code initialize()} to replace
     * the old {@code instance = this} pattern. If the FXML is reloaded, the
     * new controller instance simply overwrites the weak reference for its class
     * and the old controller becomes eligible for GC immediately.</p>
     *
     * @param controller The controller to register (must not be {@code null}).
     */
    public static <T> void register(T controller) {
        registry.put(controller.getClass(), new WeakReference<>(controller));
    }

    /**
     * Calls {@code action} with the live controller of type {@code type},
     * <em>if and only if</em> the controller is still alive (not yet GC'd).
     *
     * <p>The action is always executed on the <strong>JavaFX Application
     * Thread</strong> — wrapping in {@link Platform#runLater} if the caller
     * is on a background thread — so UI node access inside the lambda is safe.</p>
     *
     * <p>If no controller of the requested type is registered, or if the weak
     * reference has been cleared, this method is a no-op. No exception is
     * thrown and no null check is required at the call site.</p>
     *
     * @param type   Exact class of the target controller.
     * @param action Lambda to invoke with the controller instance.
     * @param <T>    Controller type.
     */
    @SuppressWarnings("unchecked")
    public static <T> void ifPresent(Class<T> type, Consumer<T> action) {
        WeakReference<?> ref = registry.get(type);
        if (ref == null) return;

        T controller = (T) ref.get();
        if (controller == null) {
            // Controller was GC'd — remove the stale entry
            registry.remove(type, ref);
            return;
        }

        if (Platform.isFxApplicationThread()) {
            action.accept(controller);
        } else {
            Platform.runLater(() -> action.accept(controller));
        }
    }
}
