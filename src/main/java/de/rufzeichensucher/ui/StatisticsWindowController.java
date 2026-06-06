package de.rufzeichensucher.ui;

import de.rufzeichensucher.data.CallsignStatistics;
import de.rufzeichensucher.model.CallsignEntry;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatisticsWindowController {

    private static final Logger log = LoggerFactory.getLogger(StatisticsWindowController.class);

    // ---- FXML fields --------------------------------------------------------

    @FXML private TabPane   tabPane;
    @FXML private GridPane  overviewGrid;
    @FXML private VBox      licenseBox;
    @FXML private StackPane stateChartPane;
    @FXML private TableView<CallsignStatistics.StateStats>              stateTable;
    @FXML private TableColumn<CallsignStatistics.StateStats, String>    stateNameCol;
    @FXML private TableColumn<CallsignStatistics.StateStats, Long>      stateCountCol;
    @FXML private TableColumn<CallsignStatistics.StateStats, Double>    statePerCapCol;
    @FXML private VBox      prefixBox;
    @FXML private BorderPane dupPane;
    @FXML private StackPane heatmapPane;
    @FXML private StackPane computingPane;

    // ---- State --------------------------------------------------------------

    private boolean isDarkMode;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "statistics-worker");
        t.setDaemon(true);
        return t;
    });

    // ---- Public API ---------------------------------------------------------

    public void setup(CallsignStatistics statistics,
                      List<CallsignEntry> entries,
                      List<CallsignEntry> duplicates,
                      boolean isDarkMode) {
        this.isDarkMode = isDarkMode;
        computingPane.setVisible(true);
        tabPane.setDisable(true);

        executor.submit(() -> {
            var result = statistics.compute(entries, duplicates);
            Platform.runLater(() -> {
                computingPane.setVisible(false);
                tabPane.setDisable(false);
                populate(result);
            });
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ---- Populate all tabs --------------------------------------------------

    private void populate(CallsignStatistics.Result r) {
        buildOverviewTab(r);
        buildLicenseTab(r);
        buildStateTab(r);
        buildPrefixTab(r);
        buildDupTab(r);
        buildHeatmapTab(r);
    }

    // ---- Übersicht ----------------------------------------------------------

    private void buildOverviewTab(CallsignStatistics.Result r) {
        overviewGrid.getChildren().clear();

        var cards = new Object[][]{
                {"Rufzeichen gesamt",  String.valueOf(r.total())},
                {"Adressen gesperrt",  r.blockedAddresses() + " (" +
                        (r.total() > 0
                            ? String.format("%.1f%%", 100.0 * r.blockedAddresses() / r.total())
                            : "–") + ")"},
                {"Duplikate",          String.valueOf(r.duplicates().size())},
        };
        int col = 0, row = 0;
        for (var c : cards) {
            overviewGrid.add(metricCard((String) c[0], (String) c[1]), col++, row);
            if (col == 3) { col = 0; row++; }
        }
        row++; col = 0;

        // License class breakdown
        for (var e : r.byLicenseClass().entrySet()) {
            overviewGrid.add(metricCard("Klasse " + e.getKey(), String.valueOf(e.getValue())), col++, row);
            if (col == 3) { col = 0; row++; }
        }

        // Let columns grow equally
        if (overviewGrid.getColumnConstraints().isEmpty()) {
            for (int i = 0; i < 3; i++) {
                var cc = new ColumnConstraints();
                cc.setHgrow(Priority.ALWAYS);
                cc.setFillWidth(true);
                overviewGrid.getColumnConstraints().add(cc);
            }
        }
    }

    private VBox metricCard(String label, String value) {
        var valLabel  = new Label(value);
        valLabel.getStyleClass().add("metric-value");

        var nameLabel = new Label(label);
        nameLabel.getStyleClass().add("metric-label");

        var card = new VBox(4, valLabel, nameLabel);
        card.getStyleClass().add("metric-card");
        card.setAlignment(Pos.CENTER);
        return card;
    }

    // ---- Lizenzklassen ------------------------------------------------------

    private static final String[] SLICE_COLORS =
            {"#2196F3", "#FF9800", "#4CAF50", "#9E9E9E", "#E91E63", "#9C27B0"};

    // ---- Heatmap constants --------------------------------------------------

    private static final double MAP_LAT_MIN = 47.0, MAP_LAT_MAX = 55.5;
    private static final double MAP_LON_MIN = 5.5,  MAP_LON_MAX = 15.5;
    private static final int    MAP_W = 480,  MAP_H = 630;
    // Render at 4× for crisp display on HiDPI/4K screens
    private static final int    RENDER_W = MAP_W * 4, RENDER_H = MAP_H * 4;

    // Separate typed arrays instead of Object[][] to avoid unchecked casts
    private static final double[] CITY_LATS = {
        52.52, 53.55, 48.14, 50.94, 50.11, 48.78, 51.23, 51.34, 51.51, 51.46,
        53.08, 51.05, 52.37, 49.45, 51.48, 52.02, 50.73, 51.96, 49.01, 49.49,
        48.37, 50.08, 54.32, 50.98, 47.99, 53.63, 54.09, 51.34, 49.00, 52.13, 51.05
    };
    private static final double[] CITY_LONS = {
        13.40, 10.00, 11.58,  6.96,  8.68,  9.18,  6.78, 12.38,  7.47,  7.01,
         8.81, 13.74,  9.73, 11.08,  7.22,  8.53,  7.10,  7.63,  8.40,  8.47,
        10.90,  8.24, 10.14, 11.03,  7.84, 11.42, 12.10,  9.50, 12.10, 11.63, 13.74
    };
    private static final String[] CITY_NAMES = {
        "Berlin", "Hamburg", "München", "Köln", "Frankfurt", "Stuttgart", "Düsseldorf",
        "Leipzig", "Dortmund", "Essen", "Bremen", "Dresden", "Hannover", "Nürnberg",
        "Bochum", "Bielefeld", "Bonn", "Münster", "Karlsruhe", "Mannheim", "Augsburg",
        "Wiesbaden", "Kiel", "Erfurt", "Freiburg", "Schwerin", "Rostock", "Kassel",
        "Regensburg", "Magdeburg", "Chemnitz"
    };

    private void buildLicenseTab(CallsignStatistics.Result r) {
        licenseBox.getChildren().clear();

        var pieData = FXCollections.<PieChart.Data>observableArrayList();
        for (var e : r.byLicenseClass().entrySet()) {
            pieData.add(new PieChart.Data("Klasse " + e.getKey() + "  (" + e.getValue() + ")", e.getValue()));
        }

        var pie = new PieChart(pieData);
        pie.setTitle("Lizenzklassen");
        pie.setLegendVisible(false);
        pie.setAnimated(false);

        // Donut hole: unmanaged Circle, repositioned to the actual chart-content
        // center after every layout pass (title height shifts the center off-middle)
        var hole = new Circle();
        hole.setManaged(false);
        hole.setFill(isDarkMode ? Color.web("#2b2b2b") : Color.WHITE);

        Runnable updateHole = () -> {
            var content = pie.lookup(".chart-content");
            if (content == null) return;
            var b = content.getBoundsInParent();
            if (b.getWidth() <= 0) return;
            hole.setCenterX(b.getCenterX());
            hole.setCenterY(b.getCenterY());
            hole.setRadius(Math.min(b.getWidth(), b.getHeight()) * 0.25);
        };

        pie.layoutBoundsProperty().addListener((obs, o, n) -> Platform.runLater(updateHole));
        pie.sceneProperty().addListener((obs, o, scene) -> {
            if (scene != null) Platform.runLater(() -> {
                updateHole.run();
                applySliceColors(pieData);
            });
        });

        // Wrapper fills the VBox (which fills the ScrollPane viewport via fitToHeight)
        var wrapper = new Pane(pie, hole);
        wrapper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(wrapper, Priority.ALWAYS);
        pie.prefWidthProperty().bind(wrapper.widthProperty());
        pie.prefHeightProperty().bind(wrapper.heightProperty());

        licenseBox.getChildren().add(wrapper);
    }

    private void applySliceColors(List<PieChart.Data> data) {
        for (int i = 0; i < data.size(); i++) {
            var node = data.get(i).getNode();
            if (node != null) {
                node.setStyle("-fx-pie-color: " + SLICE_COLORS[i % SLICE_COLORS.length] + ";");
            }
        }
    }

    // ---- Bundesländer -------------------------------------------------------

    private void buildStateTab(CallsignStatistics.Result r) {
        // Chart (top 10)
        var pieData = FXCollections.<PieChart.Data>observableArrayList();
        r.byState().stream().limit(10).forEach(s ->
                pieData.add(new PieChart.Data(s.name(), s.count())));

        var pie = new PieChart(pieData);
        pie.setTitle("Bundesland-Verteilung (Top 10)");
        pie.setLegendSide(Side.RIGHT);
        pie.setAnimated(false);
        VBox.setVgrow(pie, Priority.ALWAYS);

        stateChartPane.getChildren().setAll(pie);

        // Table
        stateNameCol.setCellValueFactory(cd ->
                new ReadOnlyObjectWrapper<>(cd.getValue().name()));
        stateCountCol.setCellValueFactory(cd ->
                new ReadOnlyObjectWrapper<>(cd.getValue().count()));
        statePerCapCol.setCellValueFactory(cd ->
                new ReadOnlyObjectWrapper<>(cd.getValue().perTenThousand()));
        statePerCapCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : String.format("%.2f", v));
            }
        });

        stateTable.getItems().setAll(r.byState());
    }

    // ---- Präfixe ------------------------------------------------------------

    private void buildPrefixTab(CallsignStatistics.Result r) {
        prefixBox.getChildren().clear();

        var xAxis = new CategoryAxis();
        var yAxis = new NumberAxis();
        xAxis.setLabel("Präfix");
        yAxis.setLabel("Anzahl");

        var chart = new BarChart<>(xAxis, yAxis);
        chart.setTitle("Verteilung der Präfixe");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(4);
        chart.setBarGap(1);

        var series = new XYChart.Series<String, Number>();
        for (var e : r.byPrefix().entrySet()) {
            var item = new XYChart.Data<String, Number>(e.getKey(), e.getValue());
            series.getData().add(item);
        }
        chart.getData().add(series);

        // Tooltips with exact counts
        for (var item : series.getData()) {
            var node = item.getNode();
            if (node != null) Tooltip.install(node,
                    new Tooltip(item.getXValue() + ": " + item.getYValue()));
            item.nodeProperty().addListener((obs, o, n) -> {
                if (n != null) Tooltip.install(n,
                        new Tooltip(item.getXValue() + ": " + item.getYValue()));
            });
        }

        chart.setPrefHeight(400);
        prefixBox.getChildren().add(chart);
    }

    // ---- Duplikate ----------------------------------------------------------

    private void buildDupTab(CallsignStatistics.Result r) {
        if (r.duplicates().isEmpty()) {
            var label = new Label("Keine Duplikate gefunden.");
            label.getStyleClass().add("detail-placeholder-label");
            var box = new VBox(label);
            box.setAlignment(Pos.CENTER);
            dupPane.setCenter(box);
        } else {
            var header = new Label(r.duplicates().size() + " doppelte Rufzeichen");
            header.getStyleClass().add("section-label");
            header.setPadding(new Insets(12, 12, 6, 12));

            var list = new ListView<CallsignEntry>();
            list.getItems().setAll(r.duplicates());
            list.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(CallsignEntry item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) { setText(null); return; }
                    setText(item.getCallsign() + "  –  " + item.getName());
                }
            });

            dupPane.setTop(header);
            dupPane.setCenter(list);
        }
    }

    // ---- Heatmap ------------------------------------------------------------

    private void buildHeatmapTab(CallsignStatistics.Result r) {
        heatmapPane.getChildren().clear();

        if (r.heatPoints().isEmpty()) {
            heatmapPane.getChildren().add(new Label("Keine Geodaten verfügbar."));
            return;
        }

        // Render expensive heatmap pixel array in background thread
        executor.submit(() -> {
            var wi = renderHeatmap(r.heatPoints());
            Platform.runLater(() -> {
                if (heatmapPane.getScene() == null) return;

                // Composite background + heatmap + overlay onto one high-res Canvas (FX thread)
                var canvas = new Canvas(RENDER_W, RENDER_H);
                var gc = canvas.getGraphicsContext2D();
                gc.setFill(isDarkMode ? Color.web("#1e1e1e") : Color.web("#e8e8e8"));
                gc.fillRect(0, 0, RENDER_W, RENDER_H);
                gc.drawImage(wi, 0, 0, RENDER_W, RENDER_H);
                renderOverlay(canvas);

                // Snapshot to a single WritableImage (sync on FX thread)
                var finalImg = canvas.snapshot(null, null);

                // Display scaled to fill the available pane, preserving aspect ratio
                var imgView = new ImageView(finalImg);
                imgView.setPreserveRatio(true);
                imgView.setSmooth(true);
                imgView.fitWidthProperty().bind(heatmapPane.widthProperty());
                imgView.fitHeightProperty().bind(heatmapPane.heightProperty());

                heatmapPane.getChildren().setAll(imgView);
            });
        });

        var spinner = new ProgressIndicator();
        spinner.setMaxSize(44, 44);
        heatmapPane.getChildren().setAll(spinner);
    }

    // ---- Coordinate mapping -------------------------------------------------

    private static double lonToPx(double lon, int w) {
        return (lon - MAP_LON_MIN) / (MAP_LON_MAX - MAP_LON_MIN) * w;
    }

    private static double latToPy(double lat, int h) {
        return (1.0 - (lat - MAP_LAT_MIN) / (MAP_LAT_MAX - MAP_LAT_MIN)) * h;
    }

    // ---- Overlay (borders + cities) -----------------------------------------

    // SVG viewport dimensions (Karte_Deutschland.svg)
    private static final double SVG_W = 591.504, SVG_H = 800.504;
    // Germany bounds encoded in that SVG (derived from known reference points)
    private static final double SVG_LON_MIN = 5.87, SVG_LON_RANGE = 9.17;
    private static final double SVG_LAT_MAX = 55.05, SVG_LAT_RANGE = 7.78;

    // Affine transform: SVG coords → heatmap canvas coords
    // cx = SVG_SX * x + SVG_TX,  cy = SVG_SY * y + SVG_TY
    private static double svgSx(int w) {
        return SVG_LON_RANGE / SVG_W / (MAP_LON_MAX - MAP_LON_MIN) * w;
    }
    private static double svgTx(int w) {
        return (SVG_LON_MIN - MAP_LON_MIN) / (MAP_LON_MAX - MAP_LON_MIN) * w;
    }
    private static double svgSy(int h) {
        return SVG_LAT_RANGE / SVG_H / (MAP_LAT_MAX - MAP_LAT_MIN) * h;
    }
    private static double svgTy(int h) {
        return (MAP_LAT_MAX - SVG_LAT_MAX) / (MAP_LAT_MAX - MAP_LAT_MIN) * h;
    }

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "<path\\b[^>]*\\bid=\"([^\"]+)\"[^>]*\\bd=\"([^\"]+)\"[^>]*/?>",
            Pattern.DOTALL);

    private static void renderOverlay(Canvas canvas) {
        var gc = canvas.getGraphicsContext2D();
        int w  = (int) canvas.getWidth();
        int h  = (int) canvas.getHeight();

        // 1. Bundesland-Grenzen direkt aus SVG (korrekte Inseln / Küstenlinien)
        try (var is = StatisticsWindowController.class
                .getResourceAsStream("/AdditionalData/bundeslaender.svg")) {
            if (is != null) {
                var svgText = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                gc.save();
                // Map SVG coordinate space → canvas coordinate space
                gc.setTransform(svgSx(w), 0, 0, svgSy(h), svgTx(w), svgTy(h));
                gc.setStroke(Color.rgb(255, 255, 255, 0.55));
                // Compensate stroke width for scale (target: ~0.9 canvas pixels)
                gc.setLineWidth(0.9 / svgSx(w));

                var m = PATH_PATTERN.matcher(svgText);
                while (m.find()) {
                    gc.beginPath();
                    gc.appendSVGPath(m.group(2));
                    gc.stroke();
                }
                gc.restore();
            }
        } catch (Exception e) {
            log.warn("Could not render state borders", e);
        }

        // 2. Großstädte: Punkt + Name (alle Größen proportional zur Canvas-Auflösung)
        double scale = (double) w / MAP_W;
        double dotR      = 2.5  * scale;
        double textOffX  = 4.0  * scale;
        double textOffY  = 3.5  * scale;
        gc.setFont(Font.font(8.5 * scale));
        for (int i = 0; i < CITY_NAMES.length; i++) {
            double px = lonToPx(CITY_LONS[i], w);
            double py = latToPy(CITY_LATS[i], h);
            gc.setFill(Color.rgb(255, 255, 255, 0.90));
            gc.fillOval(px - dotR, py - dotR, dotR * 2, dotR * 2);
            gc.setFill(Color.rgb(255, 255, 255, 0.80));
            gc.fillText(CITY_NAMES[i], px + textOffX, py + textOffY);
        }
    }

    // ---- Heatmap rendering --------------------------------------------------

    private static WritableImage renderHeatmap(List<CallsignStatistics.HeatPoint> points) {
        int width = RENDER_W, height = RENDER_H;
        float[] acc = new float[width * height];
        // Scale Gaussian radius proportionally to render resolution
        int radius = Math.max(8, 18 * RENDER_W / MAP_W);
        float[] kernel = buildGaussKernel(radius);

        for (var pt : points) {
            int px = (int) lonToPx(pt.lon(), width);
            int py = (int) latToPy(pt.lat(), height);
            float w = (float) pt.weight();

            int kSize = 2 * radius + 1;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = px + dx;
                    int ny = py + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    acc[ny * width + nx] += w * kernel[(dy + radius) * kSize + (dx + radius)];
                }
            }
        }

        float maxAcc = 0;
        for (float v : acc) if (v > maxAcc) maxAcc = v;

        var wi = new WritableImage(width, height);
        PixelWriter pw = wi.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float v = maxAcc > 0 ? acc[y * width + x] / maxAcc : 0;
                if (v <= 0.01f) {
                    pw.setColor(x, y, Color.TRANSPARENT);
                } else {
                    double hue   = (1.0 - v) * 0.65 * 360.0;
                    double alpha = Math.min(1.0, v * 1.8);
                    pw.setColor(x, y, Color.hsb(hue, 0.9, 1.0, alpha));
                }
            }
        }
        return wi;
    }

    private static float[] buildGaussKernel(int radius) {
        int size = 2 * radius + 1;
        float[] k = new float[size * size];
        double sigma = radius / 2.5;
        float sum = 0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                float v = (float) Math.exp(-(x * x + y * y) / (2 * sigma * sigma));
                k[(y + radius) * size + (x + radius)] = v;
                sum += v;
            }
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        return k;
    }
}
