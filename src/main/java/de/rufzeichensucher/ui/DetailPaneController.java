package de.rufzeichensucher.ui;

import de.rufzeichensucher.data.DMRDatabase;
import de.rufzeichensucher.data.GeocodingService;
import de.rufzeichensucher.data.GeocodingService.GeoState;
import de.rufzeichensucher.model.CallsignEntry;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailPaneController {

    private static final Logger log = LoggerFactory.getLogger(DetailPaneController.class);

    // ---- FXML fields --------------------------------------------------------
    @FXML private VBox emptyState;
    @FXML private VBox detailVBox;

    @FXML private StackPane avatarPane;
    @FXML private Label     avatarLabel;
    @FXML private Label     callsignLabel;
    @FXML private Label     nameLabel;
    @FXML private Label     licenseClassLabel;

    @FXML private Button copyButton;
    @FXML private Button qrzButton;

    @FXML private Label  addressLabel;
    @FXML private Label  secondaryLabel;
    @FXML private Label  noAddressLabel;

    @FXML private VBox   dmrContent;

    @FXML private StackPane     mapContainer;
    @FXML private Label         mapPlaceholder;
    @FXML private ProgressIndicator mapSpinner;
    @FXML private Label         mapErrorLabel;
    @FXML private ProgressBar   mapLoadingBar;
    @FXML private Button        openMapButton;

    // ---- Dependencies -------------------------------------------------------
    private DMRDatabase      dmrDatabase;
    private GeocodingService geocodingService;

    // ---- Runtime state ------------------------------------------------------
    private volatile CallsignEntry currentEntry;
    private MapTileView            mapTileView;
    private double                 lastLat, lastLon;

    private final ExecutorService bgExecutor = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "detail-bg");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ---- Lifecycle ----------------------------------------------------------

    @FXML
    private void initialize() {
        mapTileView = new MapTileView();
        StackPane.setAlignment(mapTileView, Pos.CENTER);
        mapTileView.setVisible(false);
        mapTileView.setManaged(false);
        mapContainer.getChildren().add(mapTileView);

        mapLoadingBar.progressProperty().bind(
                javafx.beans.binding.Bindings
                        .when(mapTileView.loadingProperty())
                        .then(-1.0).otherwise(0.0));
    }

    /**
     * Called from {@code MainWindowController.setup()} to inject services.
     */
    public void setup(DMRDatabase dmrDatabase, GeocodingService geocodingService,
                      boolean isDarkMode) {
        this.dmrDatabase      = dmrDatabase;
        this.geocodingService = geocodingService;
    }

    // ---- Entry display ------------------------------------------------------

    /** Called from {@code MainWindowController} on list selection change. */
    public void showEntry(CallsignEntry entry) {
        currentEntry = entry;

        if (entry == null) {
            setVisible(emptyState, true);
            setVisible(detailVBox, false);
            return;
        }

        setVisible(emptyState, false);
        setVisible(detailVBox, true);

        updateHeader(entry);
        updateAddressSection(entry);
        updateDmrSection(entry);
        resetMapSection(entry);

        checkQrz(entry);
        startGeocoding(entry);
    }

    // ---- Sections -----------------------------------------------------------

    private void updateHeader(CallsignEntry entry) {
        var cls = entry.getLicenseClass();
        avatarLabel.setText(cls);
        avatarPane.setStyle("-fx-background-color: " + avatarColor(cls)
                + "; -fx-background-radius: 22;");

        callsignLabel.setText(entry.getCallsign());
        nameLabel.setText(entry.getName());
        licenseClassLabel.setText(licenseClassName(cls));

        copyButton.setOnAction(e -> {
            var c = new ClipboardContent();
            c.putString(entry.getCallsign());
            Clipboard.getSystemClipboard().setContent(c);
        });
        setVisible(qrzButton, false);
    }

    private void updateAddressSection(CallsignEntry entry) {
        var address = entry.getAddress();
        if (address == null) {
            setVisible(addressLabel,   false);
            setVisible(secondaryLabel, false);
            setVisible(noAddressLabel, true);
        } else {
            addressLabel.setText(address);
            setVisible(addressLabel,   true);
            setVisible(noAddressLabel, false);

            var sec = entry.getSecondaryLocation();
            if (sec != null) {
                secondaryLabel.setText("Nebenstandort: " + sec);
                setVisible(secondaryLabel, true);
            } else {
                setVisible(secondaryLabel, false);
            }
        }
    }

    private void updateDmrSection(CallsignEntry entry) {
        dmrContent.getChildren().clear();
        if (dmrDatabase == null) return;

        var ids = dmrDatabase.getDmrIds(entry.getCallsign());
        if (ids.isEmpty()) {
            var lbl = new Label("Keine DMR-ID registriert");
            lbl.getStyleClass().add("detail-muted");
            dmrContent.getChildren().add(lbl);
        } else {
            dmrContent.getChildren().add(new Label(
                    ids.size() == 1 ? "1 DMR-ID registriert"
                                    : ids.size() + " DMR-IDs registriert"));
            for (var id : ids) {
                var idLabel = new Label(String.valueOf(id));
                idLabel.getStyleClass().add("dmr-id-label");
                var copyBtn = new Button("Kopieren");
                copyBtn.setOnAction(e -> {
                    var c = new ClipboardContent();
                    c.putString(String.valueOf(id));
                    Clipboard.getSystemClipboard().setContent(c);
                });
                var row = new HBox(10, idLabel, copyBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                dmrContent.getChildren().add(row);
            }
        }
    }

    private void resetMapSection(CallsignEntry entry) {
        setVisible(openMapButton, false);
        mapTileView.clear();
        if (entry.getAddress() == null) {
            showMapWidget(mapPlaceholder);
        } else {
            showMapWidget(mapSpinner);
        }
    }

    // ---- Async: QRZ.com check -----------------------------------------------

    private void checkQrz(CallsignEntry entry) {
        bgExecutor.submit(() -> {
            try {
                var request = HttpRequest.newBuilder(
                            URI.create("https://www.qrz.com/db/" + entry.getCallsign()))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(5))
                        .header("User-Agent", "RufzeichenSucher/1.0")
                        .build();
                var resp   = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                var exists = resp.statusCode() == 200;
                Platform.runLater(() -> {
                    if (entry.equals(currentEntry)) {
                        setVisible(qrzButton, exists);
                        if (exists) {
                            qrzButton.setOnAction(e -> {
                                try {
                                    Desktop.getDesktop().browse(
                                            URI.create("https://www.qrz.com/db/" + entry.getCallsign()));
                                } catch (Exception ex) {
                                    log.warn("Could not open QRZ.com", ex);
                                }
                            });
                        }
                    }
                });
            } catch (Exception ignored) {
                // button stays hidden on error
            }
        });
    }

    // ---- Async: Geocoding ---------------------------------------------------

    private void startGeocoding(CallsignEntry entry) {
        if (geocodingService == null || entry.getAddress() == null) return;

        geocodingService.geocode(entry).thenAccept(state ->
                Platform.runLater(() -> {
                    if (!entry.equals(currentEntry)) return; // stale result
                    applyGeoState(state, entry);
                }));
    }

    private void applyGeoState(GeoState state, CallsignEntry entry) {
        switch (state) {
            case GeoState.Located loc -> {
                lastLat = loc.lat();
                lastLon = loc.lon();
                showMapWidget(mapTileView);
                mapTileView.showLocation(loc.lat(), loc.lon());
            }
            case GeoState.NoAddress ignored -> showMapWidget(mapPlaceholder);
            case GeoState.Failed f -> {
                mapErrorLabel.setText("Koordinaten nicht verfügbar: " + f.reason());
                showMapWidget(mapErrorLabel);
            }
            default -> {}
        }

        if (state instanceof GeoState.Located loc) {
            setVisible(openMapButton, true);
            openMapButton.setOnAction(e -> {
                try {
                    Desktop.getDesktop().browse(URI.create(
                            "https://www.openstreetmap.org/?mlat=" + loc.lat()
                            + "&mlon=" + loc.lon() + "&zoom=14"));
                } catch (Exception ex) {
                    log.warn("Could not open OSM", ex);
                }
            });
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private static void setVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void showMapWidget(javafx.scene.Node toShow) {
        for (var child : mapContainer.getChildren()) {
            setVisible(child, child == toShow);
        }
    }

    private static String avatarColor(String cls) {
        return switch (cls) {
            case "A" -> "#2196F3";
            case "E" -> "#FF9800";
            case "N" -> "#4CAF50";
            default  -> "#9E9E9E";
        };
    }

    private static String licenseClassName(String cls) {
        return switch (cls) {
            case "A" -> "Klasse A – Amateurfunk";
            case "E" -> "Klasse E – Einsteiger";
            case "N" -> "Klasse N";
            default  -> "Lizenzklasse " + cls;
        };
    }
}
