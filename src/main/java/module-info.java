module de.rufzeichensucher {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.fasterxml.jackson.databind;
    requires org.slf4j;
    requires java.net.http;
    requires java.prefs;
    requires java.desktop;
    requires org.jetbrains.annotations;

    requires org.apache.pdfbox;

    opens de.rufzeichensucher to javafx.fxml;
    opens de.rufzeichensucher.model to com.fasterxml.jackson.databind, javafx.fxml;
    opens de.rufzeichensucher.data to javafx.fxml;
    opens de.rufzeichensucher.ui to javafx.fxml;

    exports de.rufzeichensucher;
    exports de.rufzeichensucher.model;
    exports de.rufzeichensucher.data;
    exports de.rufzeichensucher.ui;
}
