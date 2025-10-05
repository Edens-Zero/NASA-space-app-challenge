import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

/**
 * SpaceWeatherApp
 * - Dashboard: current flare highlights, latest Kp index, quick stats, animated Sun
 * - History: table of flare events and a per-day flare count chart
 * - Alerts: in-app alerts for strong flares and high Kp (geomagnetic storms)
 * - Learn: kid-friendly explanations of solar flares, CMEs, Kp index, with an interactive Sun
 * - Settings: NASA API key, refresh interval, kid mode
 *
 * Data sources: NASA DONKI API (FLR, GST, CME)
 * Get a free key: https://api.nasa.gov/
 */
public class SpaceWeatherApp extends Application {

    // UI constants
    static final int WIDTH = 1100;
    static final int HEIGHT = 720;

    // Data fetch refresh interval (minutes)
    static final int DEFAULT_REFRESH_MIN = 10;

    // NASA DONKI endpoints
    static final String DONKI_FLR = "https://api.nasa.gov/DONKI/FLR";
    static final String DONKI_GST = "https://api.nasa.gov/DONKI/GST";
    static final String DONKI_CME = "https://api.nasa.gov/DONKI/CME";

    // HTTP and JSON
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    final Gson gson = new Gson();

    // Preferences
    final Preferences prefs = Preferences.userNodeForPackage(SpaceWeatherApp.class);
    String apiKey;

    // Scheduled data refresh
    ScheduledExecutorService scheduler;

    // Data models (observable for UI)
    final ObservableList<FlareEvent> flareEvents = FXCollections.observableArrayList();
    final ObservableList<GSTEvent> gstEvents = FXCollections.observableArrayList();
    final ObservableList<CMEEvent> cmeEvents = FXCollections.observableArrayList();

    // Dashboard state
    Label lblLatestKp = new Label("Latest Kp: N/A");
    Label lblFlareSummary = new Label("Flares (last 30 days): N/A");
    Label lblLastStrongFlare = new Label("Last significant flare: N/A");
    Label lblStatus = new Label("");
    Button btnRefresh = new Button("Refresh Now");
    Canvas sunCanvas = new Canvas(520, 520);

    // History tab
    TableView<FlareEvent> flareTable = new TableView<>();
    LineChart<String, Number> flareChart;

    // Alerts tab
    ListView<String> alertsList = new ListView<>();
    CheckBox cbAlertStrongFlare = new CheckBox("Alert for strong flares (M5+ or any X-class)");
    CheckBox cbAlertHighKp = new CheckBox("Alert for geomagnetic storm (Kp ≥ 5)");
    Label lblLastAlert = new Label("");

    // Settings tab
    TextField txtApiKey = new TextField();
    Slider refreshSlider = new Slider(5, 60, DEFAULT_REFRESH_MIN);
    CheckBox cbKidMode = new CheckBox("Kid Mode (simplify language)");

    // Sun animation state
    double sunPulse = 0;
    double flareWaveTime = 0;
    Random rng = new Random();

    // Cached computed stats
    double latestKp = Double.NaN;
    Optional<FlareEvent> latestSignificantFlare = Optional.empty();

    // Date formatting
    static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    @Override
    public void start(Stage stage) {
        apiKey = prefs.get("nasaApiKey", "DEMO_KEY");

        stage.setTitle("Space Weather for Kids - Solar Flares, Storms, and the Sun");
        TabPane tabs = new TabPane();
        tabs.getTabs().addAll(createDashboardTab(), createHistoryTab(), createAlertsTab(), createLearnTab(), createSettingsTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Scene scene = new Scene(tabs, WIDTH, HEIGHT, Color.BLACK);
        stage.setScene(scene);
        stage.show();

        // Animation for the Sun
        startSunAnimation();

        // Scheduled refresh
        int initialInterval = prefs.getInt("refreshMinutes", DEFAULT_REFRESH_MIN);
        scheduleRefresh(initialInterval);

        // Wire up actions
        btnRefresh.setOnAction(e -> refreshData());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) refreshData();
        });

        // First load
        refreshData();
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    Tab createDashboardTab() {
        Tab tab = new Tab("Dashboard");

        VBox statsBox = new VBox(10,
                titleLabel("Space Weather Now"),
                lblFlareSummary,
                lblLastStrongFlare,
                lblLatestKp,
                new HBox(10, btnRefresh, lblStatus)
        );
        statsBox.setPadding(new Insets(10));
        statsBox.setAlignment(Pos.TOP_LEFT);

        // Sun canvas with a soft pane background
        StackPane sunPane = new StackPane(sunCanvas);
        sunPane.setPrefSize(540, 540);
        sunPane.setPadding(new Insets(16));
        sunPane.setStyle("-fx-background-color: linear-gradient(to bottom, #060912, #0b1320); -fx-background-radius: 10; -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 10;");

        HBox container = new HBox(20, sunPane, statsBox);
        container.setPadding(new Insets(16));
        tab.setContent(container);

        return tab;
    }

    Tab createHistoryTab() {
        Tab tab = new Tab("History");

        // Flare table
        TableColumn<FlareEvent, String> colTime = new TableColumn<>("Start");
        colTime.setCellValueFactory(c -> new SimpleStringProperty(formatTime(c.getValue().beginTime)));
        colTime.setPrefWidth(160);

        TableColumn<FlareEvent, String> colClass = new TableColumn<>("Class");
        colClass.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().classType));
        colClass.setPrefWidth(80);

        TableColumn<FlareEvent, String> colSource = new TableColumn<>("Location");
        colSource.setCellValueFactory(c -> new SimpleStringProperty(nullable(c.getValue().sourceLocation)));
        colSource.setPrefWidth(100);

        TableColumn<FlareEvent, String> colPeak = new TableColumn<>("Peak");
        colPeak.setCellValueFactory(c -> new SimpleStringProperty(formatTime(c.getValue().peakTime)));
        colPeak.setPrefWidth(160);

        TableColumn<FlareEvent, String> colLink = new TableColumn<>("Link");
        colLink.setCellValueFactory(c -> new SimpleStringProperty(nullable(c.getValue().link)));
        colLink.setPrefWidth(340);

        flareTable.getColumns().addAll(colTime, colClass, colSource, colPeak, colLink);
        flareTable.setItems(flareEvents);
        flareTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Flares per day chart
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Date");
        yAxis.setLabel("Flares");
        flareChart = new LineChart<>(xAxis, yAxis);
        flareChart.setTitle("Solar Flares per Day (last 30 days)");
        flareChart.setCreateSymbols(false);
        flareChart.setLegendVisible(false);
        flareChart.setAnimated(false);
        flareChart.setPrefHeight(300);

        VBox box = new VBox(12, titleLabel("Solar Flare History"), flareTable, flareChart);
        box.setPadding(new Insets(10));
        tab.setContent(new ScrollPane(box));

        return tab;
    }

    Tab createAlertsTab() {
        Tab tab = new Tab("Alerts");

        cbAlertStrongFlare.setSelected(prefs.getBoolean("alertStrongFlare", true));
        cbAlertHighKp.setSelected(prefs.getBoolean("alertHighKp", true));

        cbAlertStrongFlare.selectedProperty().addListener((obs, o, n) -> prefs.putBoolean("alertStrongFlare", n));
        cbAlertHighKp.selectedProperty().addListener((obs, o, n) -> prefs.putBoolean("alertHighKp", n));

        alertsList.setPrefHeight(300);

        VBox v = new VBox(12,
                titleLabel("Space Weather Alerts"),
                new Label("This tab shows alerts generated by the app based on recent data:"),
                cbAlertStrongFlare,
                cbAlertHighKp,
                new Label("Recent alerts:"),
                alertsList,
                lblLastAlert
        );
        v.setPadding(new Insets(10));
        tab.setContent(v);

        return tab;
    }

    Tab createLearnTab() {
        Tab tab = new Tab("Learn");

        cbKidMode.setSelected(prefs.getBoolean("kidMode", true));
        cbKidMode.selectedProperty().addListener((obs, o, n) -> prefs.putBoolean("kidMode", n));

        VBox content = new VBox(16,
                titleLabel("Meet Our Star: The Sun"),
                learnParagraph("The Sun is a giant ball of hot gas. It sometimes gets very active and blasts energy into space."),
                titleLabel("What is a Solar Flare?"),
                learnParagraph("A solar flare is a bright flash from the Sun. Flares are like cosmic fireworks! Scientists label them C, M, or X. X is the biggest."),
                titleLabel("What is a CME?"),
                learnParagraph("A coronal mass ejection (CME) is a huge bubble of solar material that the Sun throws out. If it heads toward Earth, it can cause space weather."),
                titleLabel("What is Kp?"),
                learnParagraph("Kp tells us how strong Earth's geomagnetic activity is. When Kp is 5 or more, we may have a geomagnetic storm. That can make colorful auroras!"),
                titleLabel("Be a Space Weather Detective"),
                learnParagraph("Look at the Dashboard to see flare counts and Kp. Big flares (M or X) and Kp 5+ can mean stronger space weather.")
        );
        content.setPadding(new Insets(10));
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);

        VBox box = new VBox(8, cbKidMode, sp);
        box.setPadding(new Insets(10));
        tab.setContent(box);

        return tab;
    }

    Tab createSettingsTab() {
        Tab tab = new Tab("Settings");

        txtApiKey.setText(apiKey);
        refreshSlider.setShowTickMarks(true);
        refreshSlider.setShowTickLabels(true);
        refreshSlider.setMajorTickUnit(10);
        refreshSlider.setMinorTickCount(4);
        Label lblRefresh = new Label("Refresh every " + (int) refreshSlider.getValue() + " minutes");
        refreshSlider.valueProperty().addListener((obs, o, n) -> lblRefresh.setText("Refresh every " + n.intValue() + " minutes"));

        Button btnSave = new Button("Save Settings");
        btnSave.setOnAction(e -> {
            apiKey = txtApiKey.getText().trim();
            prefs.put("nasaApiKey", apiKey);
            int mins = (int) refreshSlider.getValue();
            prefs.putInt("refreshMinutes", mins);
            scheduleRefresh(mins);
            toast("Settings saved");
        });

        VBox v = new VBox(12,
                titleLabel("App Settings"),
                new Label("NASA API Key (get one at api.nasa.gov):"),
                txtApiKey,
                lblRefresh,
                refreshSlider,
                btnSave
        );
        v.setPadding(new Insets(10));
        tab.setContent(v);
        return tab;
    }

    void startSunAnimation() {
        GraphicsContext g = sunCanvas.getGraphicsContext2D();
        AnimationTimer timer = new AnimationTimer() {
            long last = 0;
            @Override
            public void handle(long now) {
                if (last == 0) { last = now; return; }
                double dt = (now - last) / 1_000_000_000.0;
                last = now;
                sunPulse += dt;
                flareWaveTime += dt;
                drawSun(g);
            }
        };
        timer.start();
    }

    void drawSun(GraphicsContext g) {
        double w = sunCanvas.getWidth();
        double h = sunCanvas.getHeight();
        g.setFill(Color.rgb(8, 10, 20));
        g.fillRect(0, 0, w, h);

        double cx = w / 2.0;
        double cy = h / 2.0;
        double baseR = 160;

        // Glow layers
        for (int i = 0; i < 5; i++) {
            double r = baseR + i * 28 + Math.sin(sunPulse * 0.8 + i) * 4;
            double alpha = 0.22 - i * 0.04;
            g.setFill(Color.color(1.0, 0.7 - i * 0.1, 0.05, Math.max(0, alpha)));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
        }

        // Core
        g.setFill(Color.rgb(255, 190, 40));
        g.fillOval(cx - baseR, cy - baseR, baseR * 2, baseR * 2);

        // Gentle coronal loops/flare arcs
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4 + flareWaveTime * 0.35;
            double r = baseR + 20 + Math.sin(flareWaveTime + i) * 10;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r;
            g.setStroke(Color.color(1.0, 0.6, 0.2, 0.6));
            g.setLineWidth(2.5);
            g.strokeOval(x - 20, y - 20, 40, 40);
        }
    }

    void scheduleRefresh(int minutes) {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refreshData();
            } catch (Exception ignored) {}
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    void refreshData() {
        Platform.runLater(() -> lblStatus.setText("Refreshing..."));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(30);

        CompletableFuture<List<FlareEvent>> flrFuture = fetchFlareHistory(start, end);
        CompletableFuture<List<GSTEvent>> gstFuture = fetchGSTHistory(start, end);
        CompletableFuture<List<CMEEvent>> cmeFuture = fetchCMEHistory(start, end);

        CompletableFuture.allOf(flrFuture, gstFuture, cmeFuture).thenAccept(v -> {
            List<FlareEvent> flrs = flrFuture.join();
            List<GSTEvent> gsts = gstFuture.join();
            List<CMEEvent> cmes = cmeFuture.join();

            Platform.runLater(() -> {
                flareEvents.setAll(flrs);
                gstEvents.setAll(gsts);
                cmeEvents.setAll(cmes);

                updateDashboardStats();
                updateFlareChart(flrs);

                generateAlerts(flrs, gsts);
                lblStatus.setText("Updated: " + LocalTime.now().withSecond(0).withNano(0));
            });
        }).exceptionally(ex -> {
            Platform.runLater(() -> {
                lblStatus.setText("Error: " + ex.getMessage());
                toast("Failed to refresh data");
            });
            return null;
        });
    }

    CompletableFuture<List<FlareEvent>> fetchFlareHistory(LocalDate start, LocalDate end) {
        String url = DONKI_FLR + "?startDate=" + start.format(ISO_FMT) + "&endDate=" + end.format(ISO_FMT) + "&api_key=" + apiKey;
        return httpGetJsonArray(url, FlareEvent[].class).thenApply(list -> Arrays.asList(list));
    }

    CompletableFuture<List<GSTEvent>> fetchGSTHistory(LocalDate start, LocalDate end) {
        String url = DONKI_GST + "?startDate=" + start.format(ISO_FMT) + "&endDate=" + end.format(ISO_FMT) + "&api_key=" + apiKey;
        return httpGetJsonArray(url, GSTEvent[].class).thenApply(list -> Arrays.asList(list));
    }

    CompletableFuture<List<CMEEvent>> fetchCMEHistory(LocalDate start, LocalDate end) {
        String url = DONKI_CME + "?startDate=" + start.format(ISO_FMT) + "&endDate=" + end.format(ISO_FMT) + "&api_key=" + apiKey;
        return httpGetJsonArray(url, CMEEvent[].class).thenApply(list -> Arrays.asList(list));
    }

    <T> CompletableFuture<T[]> httpGetJsonArray(String url, Class<T[]> clazz) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
                    return gson.fromJson(resp.body(), clazz);
                });
    }

    void updateDashboardStats() {
        // Flare counts
        long cCount = flareEvents.stream().filter(f -> f.classType != null && f.classType.startsWith("C")).count();
        long mCount = flareEvents.stream().filter(f -> f.classType != null && f.classType.startsWith("M")).count();
        long xCount = flareEvents.stream().filter(f -> f.classType != null && f.classType.startsWith("X")).count();
        lblFlareSummary.setText(String.format("Flares (last 30 days): C=%d, M=%d, X=%d", cCount, mCount, xCount));

        // Latest significant flare
        latestSignificantFlare = flareEvents.stream()
                .filter(this::isSignificantFlare)
                .max(Comparator.comparing(f -> parseTime(f.beginTime)));
        if (latestSignificantFlare.isPresent()) {
            FlareEvent f = latestSignificantFlare.get();
            lblLastStrongFlare.setText("Last significant flare: " + f.classType + " at " + formatTime(f.beginTime));
        } else {
            lblLastStrongFlare.setText("Last significant flare: None in past 30 days");
        }

        // Latest Kp
        latestKp = computeLatestKp(gstEvents);
        if (Double.isNaN(latestKp)) lblLatestKp.setText("Latest Kp: N/A");
        else lblLatestKp.setText(String.format("Latest Kp: %.1f", latestKp));
    }

    void updateFlareChart(List<FlareEvent> flrs) {
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        for (int i = 0; i < 30; i++) {
            perDay.put(LocalDate.now().minusDays(29 - i), 0);
        }
        for (FlareEvent f : flrs) {
            LocalDate d = parseTime(f.beginTime).toLocalDate();
            perDay.computeIfPresent(d, (k, v) -> v + 1);
        }

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Map.Entry<LocalDate, Integer> e : perDay.entrySet()) {
            series.getData().add(new XYChart.Data<>(e.getKey().format(ISO_FMT), e.getValue()));
        }

        flareChart.getData().setAll(series);
    }

    void generateAlerts(List<FlareEvent> flrs, List<GSTEvent> gsts) {
        List<String> newAlerts = new ArrayList<>();

        if (cbAlertStrongFlare.isSelected()) {
            Optional<FlareEvent> strong = flrs.stream()
                    .filter(this::isSignificantFlare)
                    .max(Comparator.comparing(f -> parseTime(f.beginTime)));
            strong.ifPresent(f -> newAlerts.add("Strong flare: " + f.classType + " at " + formatTime(f.beginTime)));
        }

        if (cbAlertHighKp.isSelected()) {
            double kp = computeLatestKp(gsts);
            if (!Double.isNaN(kp) && kp >= 5.0) {
                newAlerts.add(String.format("Geomagnetic storm: Kp %.1f (auroras possible!)", kp));
            }
        }

        if (!newAlerts.isEmpty()) {
            alertsList.getItems().addAll(newAlerts);
            lblLastAlert.setText("Last alert at " + LocalTime.now().withSecond(0).withNano(0));
            toast("Space weather alert");
        }
    }

    double computeLatestKp(List<GSTEvent> gsts) {
        GSTKpEntry latest = null;
        for (GSTEvent e : gsts) {
            if (e.allKpIndex == null) continue;
            for (GSTKpEntry kp : e.allKpIndex) {
                if (kp.observedTime == null) continue;
                if (latest == null || parseTime(kp.observedTime).isAfter(parseTime(latest.observedTime))) {
                    latest = kp;
                }
            }
        }
        return latest == null ? Double.NaN : (latest.kpIndex == null ? Double.NaN : latest.kpIndex);
    }

    boolean isSignificantFlare(FlareEvent f) {
        if (f.classType == null) return false;
        char level = Character.toUpperCase(f.classType.charAt(0));
        double mag = parseFlareMagnitude(f.classType);
        if (level == 'X') return true;
        return level == 'M' && mag >= 5.0;
    }

    double parseFlareMagnitude(String classType) {
        try {
            String s = classType.substring(1);
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    // Utilities
    static String formatTime(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            ZonedDateTime zdt = parseTime(iso);
            return DISPLAY_FMT.format(zdt.withZoneSameInstant(ZoneOffset.UTC));
        } catch (Exception e) {
            return iso;
        }
    }

    static ZonedDateTime parseTime(String iso) {
        // DONKI uses Z suffix; handle cases with/without offset
        if (iso == null) return ZonedDateTime.now(ZoneOffset.UTC);
        try {
            if (iso.endsWith("Z")) {
                return ZonedDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME).withZoneSameInstant(ZoneOffset.UTC);
            } else {
                return ZonedDateTime.parse(iso).withZoneSameInstant(ZoneOffset.UTC);
            }
        } catch (Exception e) {
            // fallback
            try {
                return LocalDateTime.parse(iso.replace("Z", "")).atZone(ZoneOffset.UTC);
            } catch (Exception ex) {
                return ZonedDateTime.now(ZoneOffset.UTC);
            }
        }
    }

    Label titleLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #eef;");
        return l;
    }

    Label learnParagraph(String text) {
        boolean kidMode = prefs.getBoolean("kidMode", true);
        String t = kidMode ? text : text + " (Detailed mode)";
        Label l = new Label(t);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: #dde; -fx-font-size: 14px;");
        return l;
    }

    String nullable(String s) {
        return s == null ? "" : s;
    }

    void toast(String message) {
        lblStatus.setText(message);
    }

    // Data classes for NASA DONKI
    static class FlareEvent {
        @SerializedName("flrID") String flrID;
        @SerializedName("beginTime") String beginTime;
        @SerializedName("peakTime") String peakTime;
        @SerializedName("endTime") String endTime;
        @SerializedName("classType") String classType;
        @SerializedName("sourceLocation") String sourceLocation;
        @SerializedName("activeRegionNum") Integer activeRegionNum;
        @SerializedName("link") String link;
    }

    static class GSTEvent {
        @SerializedName("gstID") String gstID;
        @SerializedName("startTime") String startTime;
        @SerializedName("allKpIndex") List<GSTKpEntry> allKpIndex;
        @SerializedName("link") String link;
    }

    static class GSTKpEntry {
        @SerializedName("observedTime") String observedTime;
        @SerializedName("kpIndex") Double kpIndex;
        @SerializedName("source") String source;
    }

    static class CMEEvent {
        @SerializedName("activityID") String activityID;
        @SerializedName("startTime") String startTime;
        @SerializedName("link") String link;
        // Some CME entries include speed data nested; omitted for brevity
    }

    public static void main(String[] args) {
        launch(args);
    }
}