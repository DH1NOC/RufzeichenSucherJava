package de.rufzeichensucher;

/**
 * Non-JavaFX entry point required for non-modular jpackage launchers.
 * The JVM cannot directly launch a class that extends Application
 * when running from the classpath (unnamed module).
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
