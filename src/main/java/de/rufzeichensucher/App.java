package de.rufzeichensucher;

import de.rufzeichensucher.data.CallsignDataManager;
import de.rufzeichensucher.data.CallsignStatistics;
import de.rufzeichensucher.data.DMRDatabase;
import de.rufzeichensucher.data.GeocodingService;
import de.rufzeichensucher.ui.MainWindowController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class App extends Application {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    /** Set once before any controller is created; read by controllers. */
    public static boolean isDarkMode = false;

    private final CallsignDataManager dataManager          = new CallsignDataManager();
    private final DMRDatabase         dmrDatabase          = new DMRDatabase();
    private final GeocodingService    geocodingService     = new GeocodingService();
    private final CallsignStatistics  callsignStatistics   = new CallsignStatistics();

    @Override
    public void start(Stage primaryStage) throws IOException {
        isDarkMode = isSystemDarkMode();

        var loader = new FXMLLoader(
                App.class.getResource("/de/rufzeichensucher/main-window.fxml"));
        Parent root = loader.load();

        var scene = new Scene(root, 1200, 750);

        // Base stylesheet (light + neutral)
        scene.getStylesheets().add(
                Objects.requireNonNull(App.class.getResource("/de/rufzeichensucher/styles.css")).toExternalForm());

        // Dark-mode overlay stylesheet applied on top when OS is in dark mode
        if (isDarkMode) {
            log.info("Dark mode detected – applying dark theme");
            scene.getStylesheets().add(
                    Objects.requireNonNull(App.class.getResource("/de/rufzeichensucher/dark-theme.css")).toExternalForm());
        }

        primaryStage.setTitle("Rufzeichen-Sucher");
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(550);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Wire up controller after stage is shown so that JavaFX is fully initialised
        MainWindowController controller = loader.getController();

        // Start DMR database (own short-lived executor)
        var dmrExecutor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "dmr-init");
            t.setDaemon(true);
            return t;
        });
        dmrDatabase.init(dmrExecutor);
        dmrExecutor.shutdown();

        geocodingService.init();
        controller.setup(dataManager, dmrDatabase, geocodingService, callsignStatistics);

        // Start callsign data pipeline (shows loading overlay automatically)
        dataManager.init();
    }

    @Override
    public void stop() {
        dataManager.shutdown();
        geocodingService.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }

    // -------------------------------------------------------------------------

    /**
     * Detects whether the host OS is currently in dark mode.
     * Returns {@code false} on any error or unsupported platform.
     */
    private static boolean isSystemDarkMode() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                return runAndCheck(
                        new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"},
                        "Dark");
            }
            if (os.contains("win")) {
                // AppsUseLightTheme == 0  →  dark mode
                return runAndCheck(
                        new String[]{"reg", "query",
                                "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                                "/v", "AppsUseLightTheme"},
                        "0x0");
            }
            if (os.contains("linux") || os.contains("nux")) {
                // GNOME: gtk-theme name contains "dark"
                return runAndCheck(
                        new String[]{"gsettings", "get",
                                "org.gnome.desktop.interface", "gtk-theme"},
                        "dark");
            }
        } catch (Exception e) {
            log.debug("Dark-mode detection failed: {}", e.getMessage());
        }
        return false;
    }

    /** Runs a process, waits up to 1 s, and checks if stdout contains {@code marker} (case-insensitive). */
    private static boolean runAndCheck(String[] cmd, String marker) throws Exception {
        var proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        try {
            if (!proc.waitFor(1, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            try (var stdout = proc.getInputStream()) {
                var output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();
                return output.toLowerCase(Locale.ROOT).contains(marker.toLowerCase(Locale.ROOT));
            }
        } finally {
            proc.destroy();
        }
    }
}
