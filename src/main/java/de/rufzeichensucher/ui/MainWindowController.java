package de.rufzeichensucher.ui;

import de.rufzeichensucher.data.CallsignDataManager;
import de.rufzeichensucher.data.CallsignStatistics;
import de.rufzeichensucher.data.DMRDatabase;
import de.rufzeichensucher.data.GeocodingService;
import de.rufzeichensucher.model.CallsignEntry;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.geometry.Pos;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainWindowController {

    private static final Logger log = LoggerFactory.getLogger(MainWindowController.class);
    private static final int MAX_RESULTS = 300;

    private static final DateTimeFormatter STAND_FMT =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.GERMAN);

    // ---- FXML fields --------------------------------------------------------
    @FXML @SuppressWarnings("unused") private StackPane rootPane;
    @FXML private Button    statisticsButton;
    @FXML private DetailPaneController detailPaneController;
    @FXML private Button refreshButton;
    @FXML private Label standDateLabel;

    @FXML private TextField searchField;
    @FXML private ToggleGroup searchModeGroup;
    @FXML @SuppressWarnings("unused") private ToggleButton searchByCallsign;
    @FXML private ToggleButton searchByName;
    @FXML private ToggleButton searchByCity;
    @FXML private Label resultCountLabel;

    @FXML private ListView<CallsignEntry> callsignList;

    @FXML private StackPane loadingOverlay;
    @FXML private VBox      stepsBox;

    // ---- State --------------------------------------------------------------
    private CallsignDataManager dataManager;
    private DMRDatabase         dmrDatabase;
    private CallsignStatistics  callsignStatistics;
    private boolean             isDarkMode;

    private final ObservableList<CallsignEntry> displayedEntries =
            FXCollections.observableArrayList();

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "search-worker");
        t.setDaemon(true);
        return t;
    });

    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(100));
    private Future<?> pendingSearch = null;

    /** Minimum time the loading overlay stays visible once shown. */
    private final PauseTransition overlayHideDelay = new PauseTransition(Duration.millis(1500));
    private boolean overlayShowRequested = false;

    // Checklist step icons (set in initialize, updated as data loads)
    private enum StepState { PENDING, ACTIVE, DONE, FAILED }
    private StackPane stepIconDownload, stepIconParse, stepIconDmr;

    // ---- Lifecycle ----------------------------------------------------------

    @FXML
    private void initialize() {
        callsignList.setItems(displayedEntries);
        callsignList.setCellFactory(lv -> new CallsignListCell());

        searchDebounce.setOnFinished(e -> triggerSearch());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchDebounce.playFromStart());
        searchModeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n != null) triggerSearch();
        });

        overlayHideDelay.setOnFinished(e -> loadingOverlay.setVisible(false));

        stepIconDownload = makeStepIcon(StepState.PENDING);
        stepIconParse    = makeStepIcon(StepState.PENDING);
        stepIconDmr      = makeStepIcon(StepState.PENDING);
        stepsBox.getChildren().addAll(
                makeStepRow(stepIconDownload, "Rufzeichenliste herunterladen"),
                makeStepRow(stepIconParse,    "Rufzeichenliste verarbeiten"),
                makeStepRow(stepIconDmr,      "DMR-Daten laden")
        );

        refreshButton.setOnAction(e -> {
            if (dataManager != null) dataManager.forceRefresh();
        });
    }

    /**
     * Called from {@code App} after FXML loading, before the stage is shown.
     */
    public void setup(CallsignDataManager dataManager, DMRDatabase dmrDatabase,
                      GeocodingService geocodingService, CallsignStatistics callsignStatistics) {
        this.dataManager         = dataManager;
        this.dmrDatabase         = dmrDatabase;
        this.callsignStatistics  = callsignStatistics;
        this.isDarkMode          = de.rufzeichensucher.App.isDarkMode;

        statisticsButton.setDisable(true);
        statisticsButton.setOnAction(e -> openStatisticsWindow());

        if (detailPaneController != null) {
            detailPaneController.setup(dmrDatabase, geocodingService, de.rufzeichensucher.App.isDarkMode);
            callsignList.getSelectionModel().selectedItemProperty().addListener(
                    (obs, old, sel) -> detailPaneController.showEntry(sel));
        }

        bindDataManager();
        bindDmrDatabase();

        // Populate list if data is already available (loaded from cache)
        if (dataManager.getState() == CallsignDataManager.State.DONE && !dataManager.getEntries().isEmpty()) {
            populateAll();
            statisticsButton.setDisable(false);
        } else if (dataManager.getState() == CallsignDataManager.State.DOWNLOADING) {
            showOverlay();
            applyStepState(stepIconDownload, StepState.ACTIVE);
        } else if (dataManager.getState() == CallsignDataManager.State.PARSING) {
            showOverlay();
            applyStepState(stepIconDownload, StepState.DONE);
            applyStepState(stepIconParse,    StepState.ACTIVE);
        }
    }

    // ---- Data binding -------------------------------------------------------

    private void bindDataManager() {
        dataManager.stateProperty().addListener((obs, oldState, newState) -> {
            switch (newState) {
                case DOWNLOADING -> {
                    showOverlay();
                    applyStepState(stepIconDownload, StepState.ACTIVE);
                    applyStepState(stepIconParse,    StepState.PENDING);
                }
                case PARSING -> {
                    showOverlay();
                    applyStepState(stepIconDownload, StepState.DONE);
                    applyStepState(stepIconParse,    StepState.ACTIVE);
                }
                case DONE -> {
                    applyStepState(stepIconDownload, StepState.DONE);
                    applyStepState(stepIconParse,    StepState.DONE);
                    populateAll();
                    updateStandDate();
                    scheduleOverlayHide();
                    statisticsButton.setDisable(false);
                }
                case FAILED -> {
                    if (oldState == CallsignDataManager.State.DOWNLOADING)
                        applyStepState(stepIconDownload, StepState.FAILED);
                    else
                        applyStepState(stepIconParse, StepState.FAILED);
                    scheduleOverlayHide();
                    if (dataManager.getEntries().isEmpty()) {
                        resultCountLabel.setText(
                            "Keine Daten verfügbar – bitte Netzwerk prüfen und Aktualisieren klicken.");
                    }
                }
                default -> {}
            }
        });
    }

    private void bindDmrDatabase() {
        applyDmrStepState(dmrDatabase.getState());
        dmrDatabase.stateProperty().addListener((obs, old, state) -> applyDmrStepState(state));
    }

    private void applyDmrStepState(DMRDatabase.State state) {
        switch (state) {
            case IDLE    -> applyStepState(stepIconDmr, StepState.PENDING);
            case LOADING -> applyStepState(stepIconDmr, StepState.ACTIVE);
            case DONE    -> {
                applyStepState(stepIconDmr, StepState.DONE);
                callsignList.refresh();
            }
            case FAILED  -> applyStepState(stepIconDmr, StepState.FAILED);
        }
    }

    private void showOverlay() {
        overlayHideDelay.stop();
        overlayShowRequested = true;
        loadingOverlay.setVisible(true);
    }

    private HBox makeStepRow(StackPane iconPane, String text) {
        var label = new Label(text);
        label.setStyle("-fx-font-size: 13px;");
        var row = new HBox(10, iconPane, label);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private StackPane makeStepIcon(StepState state) {
        var pane = new StackPane();
        pane.setPrefSize(20, 20);
        pane.setMinSize(20, 20);
        pane.setMaxSize(20, 20);
        applyStepState(pane, state);
        return pane;
    }

    private void applyStepState(StackPane iconPane, StepState state) {
        iconPane.getChildren().clear();
        switch (state) {
            case PENDING -> {
                var l = new Label("–");
                l.setStyle("-fx-text-fill: -fx-mid-text-color; -fx-font-size: 14px;");
                iconPane.getChildren().add(l);
            }
            case ACTIVE -> {
                var pi = new ProgressIndicator(-1);
                pi.setPrefSize(16, 16);
                pi.setMaxSize(16, 16);
                iconPane.getChildren().add(pi);
            }
            case DONE -> {
                var l = new Label("✓");
                l.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold; -fx-font-size: 15px;");
                iconPane.getChildren().add(l);
            }
            case FAILED -> {
                var l = new Label("✗");
                l.setStyle("-fx-text-fill: #E53935; -fx-font-weight: bold; -fx-font-size: 15px;");
                iconPane.getChildren().add(l);
            }
        }
    }

    private void scheduleOverlayHide() {
        if (overlayShowRequested) {
            overlayHideDelay.playFromStart();
            overlayShowRequested = false;
        }
    }

    private void updateStandDate() {
        var date = dataManager.getStandDate();
        if (date != null) {
            standDateLabel.setText("Stand: " + date.format(STAND_FMT));
        } else {
            standDateLabel.setText("Stand: –");
        }
    }

    // ---- Search / filter ----------------------------------------------------

    private void triggerSearch() {
        if (dataManager == null || dataManager.getEntries().isEmpty()) return;

        if (pendingSearch != null && !pendingSearch.isDone()) {
            pendingSearch.cancel(true);
        }

        var query = searchField.getText();
        var mode = getSelectedMode();
        var allEntries = dataManager.getEntries();

        pendingSearch = searchExecutor.submit(() -> {
            var results = filter(allEntries, query, mode);
            Platform.runLater(() -> applyResults(results, query));
        });
    }

    private enum SearchMode { CALLSIGN, NAME, CITY }

    private SearchMode getSelectedMode() {
        var sel = searchModeGroup.getSelectedToggle();
        if (sel == searchByName) return SearchMode.NAME;
        if (sel == searchByCity) return SearchMode.CITY;
        return SearchMode.CALLSIGN;
    }

    private List<CallsignEntry> filter(List<CallsignEntry> entries, String query, SearchMode mode) {
        if (query == null || query.isBlank()) {
            return entries.size() <= MAX_RESULTS ? entries : entries.subList(0, MAX_RESULTS);
        }
        var q = query.strip().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> switch (mode) {
                    case CALLSIGN -> e.getCallsign().toLowerCase(Locale.ROOT).contains(q);
                    case NAME     -> e.getName().toLowerCase(Locale.ROOT).contains(q);
                    case CITY     -> {
                        var city = e.getCity();
                        var zip  = e.getZip();
                        yield (city != null && city.toLowerCase(Locale.ROOT).contains(q))
                           || (zip  != null && zip.contains(q));
                    }
                })
                .limit(MAX_RESULTS)
                .toList();
    }

    private void applyResults(List<CallsignEntry> results, String query) {
        displayedEntries.setAll(results);
        var total = dataManager.getEntries().size();
        if (query != null && !query.isBlank()) {
            resultCountLabel.setText(results.size() + " Treffer (von " + total + ")");
        } else if (!results.isEmpty()) {
            resultCountLabel.setText(total + " Einträge");
        } else {
            resultCountLabel.setText("");
        }
    }

    private void populateAll() {
        var entries = dataManager.getEntries();
        displayedEntries.setAll(
                entries.size() <= MAX_RESULTS ? entries : entries.subList(0, MAX_RESULTS));
        if (!entries.isEmpty()) {
            var q = searchField.getText();
            if (q != null && !q.isBlank()) {
                triggerSearch();
            } else {
                resultCountLabel.setText(entries.size() + " Einträge");
            }
        }
        updateStandDate();
    }

    // ---- Statistics window --------------------------------------------------

    private Stage statisticsStage;

    private void openStatisticsWindow() {
        if (statisticsStage != null && statisticsStage.isShowing()) {
            statisticsStage.toFront();
            return;
        }
        try {
            var loader = new FXMLLoader(
                    getClass().getResource("/de/rufzeichensucher/statistics-window.fxml"));
            javafx.scene.Parent root = loader.load();
            StatisticsWindowController ctrl = loader.getController();

            var scene = new Scene(root, 740, 580);
            scene.getStylesheets().add(
                    Objects.requireNonNull(getClass().getResource("/de/rufzeichensucher/styles.css")).toExternalForm());
            if (isDarkMode) {
                scene.getStylesheets().add(
                        Objects.requireNonNull(getClass().getResource("/de/rufzeichensucher/dark-theme.css")).toExternalForm());
            }

            statisticsStage = new Stage();
            statisticsStage.setTitle("Statistiken – Rufzeichen-Sucher");
            statisticsStage.setScene(scene);
            statisticsStage.setMinWidth(720);
            statisticsStage.setMinHeight(540);
            statisticsStage.setOnHidden(e -> {
                ctrl.shutdown();
                statisticsStage = null;
            });
            statisticsStage.show();

            ctrl.setup(callsignStatistics,
                       dataManager.getEntries(),
                       dataManager.getDuplicates(),
                       isDarkMode);
        } catch (Exception e) {
            log.error("Failed to open statistics window", e);
        }
    }

    // ---- Custom list cell ---------------------------------------------------

    private class CallsignListCell extends ListCell<CallsignEntry> {

        private final HBox root = new HBox(8);
        private final Label badgeClass = new Label();
        private final Label callsignLabel = new Label();
        private final Label nameLabel = new Label();
        private final Label cityLabel = new Label();
        private final Label badgeDmr = new Label("DMR");
        private final HBox callsignRow = new HBox(6, callsignLabel, badgeDmr);
        private final VBox textBlock = new VBox(1, callsignRow, nameLabel, cityLabel);

        {
            badgeClass.getStyleClass().addAll("badge");
            badgeClass.setMinWidth(22);
            badgeClass.setAlignment(javafx.geometry.Pos.CENTER);

            badgeDmr.getStyleClass().addAll("badge", "badge-dmr");
            badgeDmr.setVisible(false);
            badgeDmr.setManaged(false);

            callsignLabel.getStyleClass().add("callsign-cell-callsign");
            nameLabel.getStyleClass().add("callsign-cell-name");
            cityLabel.getStyleClass().add("callsign-cell-city");

            HBox.setHgrow(textBlock, Priority.ALWAYS);
            root.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            root.getChildren().addAll(badgeClass, textBlock);
            root.getStyleClass().add("callsign-cell");
        }

        @Override
        protected void updateItem(CallsignEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            var cls = item.getLicenseClass();
            badgeClass.setText(cls);
            badgeClass.getStyleClass().removeIf(c -> c.startsWith("badge-"));
            badgeClass.getStyleClass().add("badge");
            badgeClass.getStyleClass().add(badgeStyleClass(cls));

            callsignLabel.setText(item.getCallsign());
            nameLabel.setText(item.getName());

            var cityWithZip = item.getCityWithZip();
            cityLabel.setText(cityWithZip != null ? cityWithZip : "");
            cityLabel.setManaged(cityWithZip != null);
            cityLabel.setVisible(cityWithZip != null);

            var hasDmr = dmrDatabase != null && !dmrDatabase.getDmrIds(item.getCallsign()).isEmpty();
            badgeDmr.setVisible(hasDmr);
            badgeDmr.setManaged(hasDmr);

            setGraphic(root);
        }

        private String badgeStyleClass(String cls) {
            return switch (cls) {
                case "A" -> "badge-a";
                case "E" -> "badge-e";
                case "N" -> "badge-n";
                default  -> "badge-other";
            };
        }
    }
}
