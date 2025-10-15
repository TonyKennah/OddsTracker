package uk.co.pluckier.oddstracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import uk.co.kennah.tkapi.model.MyRunner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.staticFiles;

public class WebServer {

    private final OddsTracker oddsTracker;
    private final Gson gson = new GsonBuilder()
        .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
        .create();

    public WebServer(OddsTracker oddsTracker) {
        this.oddsTracker = oddsTracker;
    }

    public void start() {
        port(8080); // The server will run on http://localhost:8080
        staticFiles.location("/public"); // Serve files from src/main/resources/public

        // Serve the main HTML page
        get("/", (req, res) -> {
            try {
                return new String(Files.readAllBytes(Paths.get(WebServer.class.getResource("/public/index.html").toURI())));
            } catch (Exception e) {
                return "Error: Could not find index.html. Make sure it's in src/main/resources/public/";
            }
        });

        // Serve the history HTML page
        get("/history.html", (req, res) -> {
            try {
                return new String(Files.readAllBytes(Paths.get(WebServer.class.getResource("/public/history.html").toURI())));
            } catch (Exception e) {
                return "Error: Could not find history.html. Make sure it's in src/main/resources/public/";
            }
        });

        // Create an API endpoint to provide the latest odds data, grouped by event time
        get("/api/odds", (req, res) -> {
            res.type("application/json");
            // We only need initial and current odds for the main page logic.
            // The backend now handles historical state internally for movement calculations.
            Map<Long, MyRunner> lastKnownRunners = oddsTracker.getLastKnownOdds(); // Used for final status and movement
            Map<Long, MyRunner> currentRunners = oddsTracker.getCurrentOdds(); // Used for live overround and display odds
            Map<Long, MyRunner> initialRunners = oddsTracker.getInitialOdds();
            Map<Long, MyRunner> previousRunners = oddsTracker.getPreviousOdds();
            Map<Long, Double> lastRecordedMovements = oddsTracker.getLastRecordedMovementMap();

            // Group runners by adjusted event time, sorting the groups by time
            // The initial snapshot is the source of truth for all runners in a race.
            Map<String, List<RunnerWithMovement>> groupedByTime = initialRunners.entrySet().stream()
                    .filter(entry -> {
                        MyRunner runner = entry.getValue();
                        return runner.getEvent() != null && !runner.getEvent().isEmpty() && runner.getOdds() != null && runner.getOdds() > 0;
                    })
                    .map(entry -> {
                        MyRunner initialRunner = entry.getValue();
                        MyRunner lastKnownRunner = lastKnownRunners.get(entry.getKey());

                        // A runner's final state (including non-runner) is based on its last known record.
                        boolean isNonRunner = lastKnownRunner != null && (lastKnownRunner.getOdds() == null || lastKnownRunner.getOdds() <= 0);

                        // Total movement is based on initial vs. last known odds.
                        double initialOdds = initialRunner.getOdds();
                        double finalOdds = (lastKnownRunner != null && lastKnownRunner.getOdds() != null) ? lastKnownRunner.getOdds() : initialOdds;
                        
                        // Use BigDecimal for precise arithmetic to avoid floating-point errors.
                        BigDecimal initialBd = BigDecimal.valueOf(initialOdds);
                        BigDecimal finalBd = BigDecimal.valueOf(finalOdds);
                        double movement = finalBd.subtract(initialBd).setScale(2, RoundingMode.HALF_UP).doubleValue();

                        // Recent movement for the arrow is based on the live polling maps.
                        MyRunner currentRunner = currentRunners.get(entry.getKey());
                        MyRunner previousRunner = previousRunners.get(entry.getKey());
                        double lastMovement = 0;
                        String lastMovementType = "NONE";

                        if (currentRunner != null && currentRunner.getOdds() != null && previousRunner != null && previousRunner.getOdds() != null) {
                            double recentMovement = currentRunner.getOdds() - previousRunner.getOdds();
                            if (recentMovement != 0) {
                                lastMovement = recentMovement;
                                lastMovementType = "RECENT";
                            }
                        }
                        // If there was no recent movement, fall back to the last recorded movement from history.
                        if (lastMovement == 0) {
                            lastMovement = lastRecordedMovements.getOrDefault(entry.getKey(), 0.0);
                            if (lastMovement != 0) {
                                lastMovementType = "HISTORICAL";
                            }
                        }

                        String status = isNonRunner ? "NON_RUNNER" : "RUNNER";
                        
                        // For the label and overround, use live odds if available, otherwise use the final historical odds.
                        // This ensures the overround is live, and the label shows the most current price.
                        double displayOdds = (currentRunner != null && currentRunner.getOdds() != null) ? currentRunner.getOdds() : finalOdds;
                        RunnerData runnerData = new RunnerData(initialRunner.getName(), displayOdds, initialRunner.getEvent(), initialOdds);

                        return new RunnerWithMovement(entry.getKey(), runnerData, movement, status, lastMovement, lastMovementType);
                    })
                    .collect(Collectors.groupingBy(runnerWithMovement -> {
                        try {
                            DateTimeFormatter originalFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
                            LocalDateTime eventDateTime = LocalDateTime.parse(runnerWithMovement.runner().event().substring(0, 16), originalFormatter);
                            return eventDateTime.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm"));
                        } catch (Exception e) {
                            return "Unparsed";
                        }
                    }, TreeMap::new, Collectors.toList()));

            // Now, calculate the overround for each group and create the final payload
            Map<String, RaceDataPayload> payload = new TreeMap<>();
            for (Map.Entry<String, List<RunnerWithMovement>> entry : groupedByTime.entrySet()) {
                List<RunnerWithMovement> runners = entry.getValue();
                double totalImpliedProbability = runners.stream()
                        .filter(r -> r.status().equals("RUNNER") && r.runner().odds() > 0)
                        .mapToDouble(r -> 1.0 / r.runner().odds())
                        .sum();
                double overround = (totalImpliedProbability * 100.0) - 100.0;
                payload.put(entry.getKey(), new RaceDataPayload(runners, overround));
            }

            return payload;
        }, gson::toJson);

        get("/api/history", (req, res) -> {
            res.type("application/json");
            String eventIdentifier = req.queryParams("eventIdentifier");
            if (eventIdentifier == null || eventIdentifier.isEmpty()) {
                res.status(400);
                return "{\"error\":\"eventIdentifier is required\"}";
            }
            RaceHistoryPayload history = oddsTracker.getRaceHistory(eventIdentifier);
            return history;
        }, gson::toJson);
    }

    /** A record to bundle a runner with its calculated odds movement. */
    private record RunnerWithMovement(long runnerId, RunnerData runner, double movement, String status, double lastMovement, String lastMovementType) {}

    /** A record to hold the final payload for a race, including runners and overround. */
    private record RaceDataPayload(List<RunnerWithMovement> runners, double overround) {}

    /** A record for a single point in a runner's odds history. */
    public record HistoryPoint(LocalDateTime timestamp, double odds) {}

    /** A record for the full history payload for a race, containing history for multiple runners. */
    public record RaceHistoryPayload(String eventIdentifier, List<RunnerHistory> runnersHistory) {}
    public record RunnerHistory(long runnerId, String runnerName, List<HistoryPoint> history) {}
}