package de.rufzeichensucher.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Canvas-based OSM slippy-map tile renderer.
 * Replaces JavaFX WebView + Leaflet to avoid the macOS HiDPI tile-clipping bug.
 */
public class MapTileView extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(MapTileView.class);

    private static final int    TILE_SIZE        = 256;
    private static final double SCROLL_THRESHOLD = 120.0; // px per zoom step (larger = coarser)

    // Shared tile store across all instances (app lifetime).
    private static final Map<String, Image>    CACHE     = new ConcurrentHashMap<>();
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    // 4 threads → max 4 concurrent tile requests; synchronous send limits HTTP/2 streams.
    private static final ExecutorService FETCH_EXEC = Executors.newFixedThreadPool(4, r -> {
        var t = new Thread(r, "tile-fetch");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Canvas         canvas = new Canvas();
    private final GraphicsContext gc    = canvas.getGraphicsContext2D();

    // Map state – centre of the current view (updated on pan/zoom)
    private double  lat         = 0;
    private double  lon         = 0;
    private int     zoom        = 14;
    private boolean hasLocation = false;
    // Fixed callsign location; never changed after showLocation()
    private double  pinLat      = 0;
    private double  pinLon      = 0;

    // Incremented on every zoom/pan so queued fetch tasks for a previous
    // view can detect they are stale and exit without doing network I/O.
    private final AtomicInteger generation = new AtomicInteger(0);

    // Pan state (live offset while dragging, reset on release)
    private double panOffsetX  = 0;
    private double panOffsetY  = 0;
    private double dragAnchorX = 0;
    private double dragAnchorY = 0;

    // Scroll accumulator so one trackpad swipe = one zoom step
    private double scrollAccum = 0;

    // Multi-marker mode (overview map)
    record Marker(double lat, double lon) {}
    private List<Marker> markers   = List.of();
    private boolean      multiMode = false;

    // Exposed so the parent can bind a ProgressBar to it
    private int pendingCount = 0;
    private final BooleanProperty loading = new SimpleBooleanProperty(false);

    // ── Constructor ───────────────────────────────────────────────────────────

    public MapTileView() {
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener(o -> redraw());
        canvas.heightProperty().addListener(o -> redraw());
        // Let mouse events fall through to the StackPane's other children (buttons).
        canvas.setMouseTransparent(true);

        var btnIn  = makeZoomButton("+", () -> { if (zoom < 18) { zoom++; refresh(); } });
        var btnOut = makeZoomButton("−", () -> { if (zoom > 1)  { zoom--; refresh(); } });
        var zoomBox = new VBox(2, btnIn, btnOut);
        zoomBox.setPadding(new Insets(8));
        zoomBox.setPickOnBounds(false); // only the buttons themselves are clickable

        getChildren().addAll(canvas, zoomBox);
        StackPane.setAlignment(zoomBox, Pos.TOP_LEFT);

        // ── Scroll zoom (accumulate so one gesture ≈ one step) ────────────────
        setOnScroll(e -> {
            scrollAccum += e.getDeltaY();
            if (scrollAccum >= SCROLL_THRESHOLD) {
                int steps = (int) (scrollAccum / SCROLL_THRESHOLD);
                scrollAccum -= steps * SCROLL_THRESHOLD;
                zoom = Math.min(18, zoom + steps);
                refresh();
            } else if (scrollAccum <= -SCROLL_THRESHOLD) {
                int steps = (int) (-scrollAccum / SCROLL_THRESHOLD);
                scrollAccum += steps * SCROLL_THRESHOLD;
                zoom = Math.max(1, zoom - steps);
                refresh();
            }
            e.consume();
        });

        // ── Drag to pan ───────────────────────────────────────────────────────
        setOnMousePressed(e -> {
            dragAnchorX = e.getX();
            dragAnchorY = e.getY();
            panOffsetX  = 0;
            panOffsetY  = 0;
        });

        setOnMouseDragged(e -> {
            panOffsetX = e.getX() - dragAnchorX;
            panOffsetY = e.getY() - dragAnchorY;
            setCursor(javafx.scene.Cursor.CLOSED_HAND);
            redraw();
        });

        setOnMouseReleased(e -> {
            setCursor(javafx.scene.Cursor.DEFAULT);
            if (panOffsetX != 0 || panOffsetY != 0) {
                // Convert the pixel offset into a new centre lat/lon
                double newPixX = lonToGlobalPx(lon, zoom) - panOffsetX;
                double newPixY = latToGlobalPy(lat, zoom) - panOffsetY;
                lon = globalPxToLon(newPixX, zoom);
                lat = globalPyToLat(newPixY, zoom);
                panOffsetX = 0;
                panOffsetY = 0;
                refresh();
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void showLocation(double lat, double lon) {
        this.lat    = lat;
        this.lon    = lon;
        this.pinLat = lat;
        this.pinLon = lon;
        this.hasLocation = true;
        this.zoom = 14;
        refresh();
    }

    public void clear() {
        hasLocation = false;
        panOffsetX = panOffsetY = 0;
        drawBackground();
    }

    /**
     * Multi-marker mode: replaces any single-pin and shows a list of locations.
     * First call auto-fits the view to the bounding box of all markers.
     * Subsequent calls (incremental geocoding updates) only redraw.
     */
    @SuppressWarnings("unused")
    public void setMarkers(List<double[]> latLons) {
        boolean wasEmpty = markers.isEmpty();
        var list = new ArrayList<Marker>(latLons.size());
        for (var m : latLons) list.add(new Marker(m[0], m[1]));
        this.markers   = list;
        this.multiMode = true;
        this.hasLocation = !markers.isEmpty();
        if (!markers.isEmpty()) {
            if (wasEmpty) { fitMarkers(); refresh(); }
            else          { redraw(); }
        } else {
            generation.incrementAndGet();
            drawBackground();
        }
    }

    private void fitMarkers() {
        double minLat = markers.stream().mapToDouble(Marker::lat).min().orElse(51.3);
        double maxLat = markers.stream().mapToDouble(Marker::lat).max().orElse(51.3);
        double minLon = markers.stream().mapToDouble(Marker::lon).min().orElse(10.4);
        double maxLon = markers.stream().mapToDouble(Marker::lon).max().orElse(10.4);
        lat = (minLat + maxLat) / 2;
        lon = (minLon + maxLon) / 2;
        panOffsetX = panOffsetY = 0;
        // Fit zoom so all markers sit within 80% of the canvas
        double w = getWidth()  > 0 ? getWidth()  * 0.8 : 640;
        double h = getHeight() > 0 ? getHeight() * 0.8 : 480;
        zoom = 6;
        for (int z = 16; z >= 1; z--) {
            double dx = lonToGlobalPx(maxLon, z) - lonToGlobalPx(minLon, z);
            double dy = latToGlobalPy(minLat, z) - latToGlobalPy(maxLat, z);
            if (dx <= w && dy <= h) { zoom = z; break; }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void refresh() {
        if (!hasLocation) return;
        generation.incrementAndGet(); // invalidate any queued fetches for the previous view
        prefetchTiles();
        redraw();
    }

    private void prefetchTiles() {
        int gen = generation.get();
        int cx  = lonToTileX(lon, zoom);
        int cy  = latToTileY(lat, zoom);
        int max = 1 << zoom;
        // Radius 1 = 3×3 = 9 tiles: covers the visible area plus one-tile margin.
        // Keeping the radius small avoids flooding the queue when zooming quickly.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int tx = Math.floorMod(cx + dx, max);
                int ty = cy + dy;
                if (ty >= 0 && ty < max) fetchIfMissing(zoom, tx, ty, gen);
            }
        }
    }

    private void fetchIfMissing(int z, int tx, int ty, int gen) {
        String key = z + "/" + tx + "/" + ty;
        if (CACHE.containsKey(key) || !IN_FLIGHT.add(key)) return;

        pendingCount++;
        updateSpinner();

        FETCH_EXEC.submit(() -> {
            // Skip immediately if the view has moved on (zoom/pan since submission).
            if (gen != generation.get()) {
                IN_FLIGHT.remove(key);
                Platform.runLater(() -> { pendingCount--; updateSpinner(); });
                return;
            }
            try {
                var request = HttpRequest.newBuilder(
                        URI.create("https://tile.openstreetmap.org/" + key + ".png"))
                        .header("User-Agent", "RufzeichenSucher/1.0")
                        .timeout(Duration.ofSeconds(15))
                        .build();
                var resp = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                IN_FLIGHT.remove(key);
                if (resp.statusCode() == 200) {
                    byte[] bytes = resp.body();
                    Platform.runLater(() -> {
                        CACHE.put(key, new Image(new ByteArrayInputStream(bytes)));
                        pendingCount--;
                        updateSpinner();
                        redraw();
                    });
                } else {
                    Platform.runLater(() -> { pendingCount--; updateSpinner(); });
                }
            } catch (Exception ex) {
                IN_FLIGHT.remove(key);
                log.debug("Tile {} failed: {}", key, ex.getMessage());
                Platform.runLater(() -> { pendingCount--; updateSpinner(); });
            }
        });
    }

    public BooleanProperty loadingProperty() { return loading; }

    private void updateSpinner() {
        loading.set(pendingCount > 0);
    }

    private void redraw() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawBackground();
        if (!hasLocation) return;

        double pixX = lonToGlobalPx(lon, zoom);
        double pixY = latToGlobalPy(lat, zoom);

        // Centre the target point, shifted by any in-progress pan
        double offX = w / 2.0 - pixX + panOffsetX;
        double offY = h / 2.0 - pixY + panOffsetY;

        int cx  = (int) Math.floor(pixX / TILE_SIZE);
        int cy  = (int) Math.floor(pixY / TILE_SIZE);
        int max = 1 << zoom;

        int halfW = (int) Math.ceil(w / 2.0 / TILE_SIZE) + 2;
        int halfH = (int) Math.ceil(h / 2.0 / TILE_SIZE) + 2;

        for (int dy = -halfH; dy <= halfH; dy++) {
            for (int dx = -halfW; dx <= halfW; dx++) {
                int globalTX  = cx + dx;
                int globalTY  = cy + dy;
                int wrappedTX = Math.floorMod(globalTX, max);
                if (globalTY < 0 || globalTY >= max) continue;

                Image img = CACHE.get(zoom + "/" + wrappedTX + "/" + globalTY);
                if (img != null && !img.isError() && img.getWidth() > 0) {
                    gc.drawImage(img,
                            offX + (double) globalTX * TILE_SIZE,
                            offY + (double) globalTY * TILE_SIZE,
                            TILE_SIZE, TILE_SIZE);
                }
            }
        }

        drawAttribution(w, h);

        if (multiMode) {
            drawClusters(offX, offY, w, h);
        } else {
            double pinX = offX + lonToGlobalPx(pinLon, zoom);
            double pinY = offY + latToGlobalPy(pinLat, zoom);
            drawPin(pinX, pinY);
        }
    }

    private void drawClusters(double offX, double offY, double w, double h) {
        int cellPx = 36;
        var cells  = new java.util.HashMap<Long, double[]>(); // → [sumX, sumY, count]
        for (var m : markers) {
            double sx = offX + lonToGlobalPx(m.lon(), zoom);
            double sy = offY + latToGlobalPy(m.lat(), zoom);
            if (sx < -cellPx || sx > w + cellPx || sy < -cellPx || sy > h + cellPx) continue;
            long key = (long) Math.floor(sx / cellPx) * 100_000L + (long) Math.floor(sy / cellPx);
            cells.merge(key, new double[]{sx, sy, 1},
                    (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + 1});
        }
        for (var c : cells.values()) {
            double cx    = c[0] / c[2];
            double cy    = c[1] / c[2];
            int    count = (int) c[2];
            if (count == 1) drawSmallPin(cx, cy);
            else            drawClusterCircle(cx, cy, count);
        }
    }

    private void drawSmallPin(double cx, double cy) {
        double r = 5;
        gc.setFill(Color.web("#1565C0"));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(1.5);
        gc.strokeOval(cx - r, cy - r, r * 2, r * 2);
    }

    private void drawClusterCircle(double cx, double cy, int count) {
        double r = Math.min(20, 12 + Math.log10(count) * 4);
        gc.setFill(Color.color(0.1, 0.55, 0.1, 0.35));
        gc.fillOval(cx - r - 5, cy - r - 5, (r + 5) * 2, (r + 5) * 2);
        gc.setFill(Color.color(0.1, 0.55, 0.1, 0.9));
        gc.fillOval(cx - r, cy - r, r * 2, r * 2);
        String label = count >= 1000 ? (count / 1000) + "k" : String.valueOf(count);
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD,
                Math.max(9, 13 - label.length())));
        gc.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText(label, cx, cy);
        gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
        gc.setTextBaseline(javafx.geometry.VPos.BASELINE);
    }

    private void drawBackground() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        gc.setFill(Color.web("#e8e8e8"));
        gc.fillRect(0, 0, w, h);
    }

    private void drawPin(double cx, double cy) {
        double r = 9;
        gc.setFill(Color.color(0, 0, 0, 0.22));
        gc.fillOval(cx - r + 2, cy - r * 2 + 2, r * 2, r * 2);
        gc.setFill(Color.web("#1565C0"));
        gc.fillOval(cx - r, cy - r * 2, r * 2, r * 2);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2.0);
        gc.strokeOval(cx - r, cy - r * 2, r * 2, r * 2);
        gc.setFill(Color.WHITE);
        gc.fillOval(cx - 3, cy - r - 3, 6, 6);
    }

    private void drawAttribution(double w, double h) {
        gc.setFont(Font.font(9.5));
        String text = "© OpenStreetMap contributors";
        gc.setFill(Color.color(1, 1, 1, 0.75));
        gc.fillRect(w - 175, h - 17, 175, 17);
        gc.setFill(Color.color(0, 0, 0, 0.65));
        gc.fillText(text, w - 172, h - 4);
    }

    private static Button makeZoomButton(String symbol, Runnable action) {
        // Use a Text graphic instead of button text so the dark theme cannot
        // override the symbol colour via -fx-text-fill.
        var icon = new Text(symbol);
        icon.setFill(Color.rgb(30, 30, 30));
        icon.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 16));

        var btn = new Button("", icon);
        btn.setPrefSize(30, 30);
        btn.setMinSize(30, 30);
        btn.setMaxSize(30, 30);
        btn.setStyle(
                "-fx-background-color: rgba(255,255,255,0.92);" +
                "-fx-background-radius: 4;" +
                "-fx-border-color: rgba(0,0,0,0.25);" +
                "-fx-border-radius: 4;" +
                "-fx-cursor: hand;" +
                "-fx-effect: dropshadow(three-pass-box,rgba(0,0,0,0.18),3,0,0,1);");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    // ── Tile / Mercator math ──────────────────────────────────────────────────

    private static int lonToTileX(double lon, int z) {
        return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
    }

    private static int latToTileY(double lat, int z) {
        double rad = Math.toRadians(lat);
        return (int) Math.floor(
                (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 << z));
    }

    private static double lonToGlobalPx(double lon, int z) {
        return (lon + 180.0) / 360.0 * (1 << z) * TILE_SIZE;
    }

    private static double latToGlobalPy(double lat, int z) {
        double rad = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * (1 << z) * TILE_SIZE;
    }

    private static double globalPxToLon(double px, int z) {
        return px / TILE_SIZE / (1 << z) * 360.0 - 180.0;
    }

    private static double globalPyToLat(double py, int z) {
        double n = Math.PI - 2.0 * Math.PI * py / TILE_SIZE / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
