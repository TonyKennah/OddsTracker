package uk.co.pluckier.oddstracker;

import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import uk.co.kennah.tkapi.client.Session;
import uk.co.kennah.tkapi.io.Writer;
import uk.co.kennah.tkapi.model.MyRunner;
import uk.co.pluckier.oddstracker.WebServer.HistoryPoint;
import uk.co.pluckier.oddstracker.WebServer.RaceHistoryPayload;
import uk.co.pluckier.oddstracker.WebServer.RunnerHistory;
import uk.co.kennah.tkapi.process.DataFetcher;

public class OddsTracker {

    private static final String SNAPSHOT_DIR = "odds_snapshots";
    private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("'odds_'yyyyMMdd_HHmmss'.ser'").withZone(ZoneId.systemDefault());
    private Map<Long, MyRunner> previousOdds;
    private Map<Long, MyRunner> currentOdds;
    private final Map<Long, MyRunner> initialOdds;
    private final Map<Long, MyRunner> lastKnownOdds;
    private final Map<Long, Double> lastRecordedMovementMap;

    public OddsTracker() {
        // Get the list of snapshot files once to avoid redundant I/O operations.
        List<File> snapshotFiles = getSnapshotFiles();

        this.previousOdds = loadPreviousOdds(snapshotFiles);
        this.currentOdds = new HashMap<>(this.previousOdds); // Initialize with last known odds
        this.initialOdds = loadInitialOdds(snapshotFiles);
        this.lastKnownOdds = loadLastKnownOdds(snapshotFiles);
        this.lastRecordedMovementMap = buildLastRecordedMovementMap(snapshotFiles);
    }

    public void startPolling() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Schedule the pollOdds method to run every 5 minutes
        scheduler.scheduleAtFixedRate(this::pollOdds, 0, 2, TimeUnit.MINUTES);
    }

    private void pollOdds() {
        System.out.println("Polling Betfair for odds changes...");
        try {
            // The "current" from the last poll now becomes the "previous" for this poll.
            this.previousOdds = new HashMap<>(this.currentOdds);

            // Your single call to get the current odds
            Map<Long, MyRunner> latestOdds = getOdds();

            if (previousOdds.isEmpty()) {
                System.out.println("Initial run. Storing current odds.");
            } else {
                System.out.println("--- Odds Updates ---");
                for (Map.Entry<Long, MyRunner> entry : latestOdds.entrySet()) {
                    Long runnerId = entry.getKey();
                    MyRunner currentRunner = entry.getValue();

                    if (previousOdds.containsKey(runnerId)) {
                        MyRunner previousRunner = previousOdds.get(runnerId);
                        Double previousOddsValue = previousRunner.getOdds() != null ? previousRunner.getOdds() : 0.0;
                        Double currentOddsValue = currentRunner.getOdds() != null ? currentRunner.getOdds() : 0.0;

                        if (!previousOddsValue.equals(currentOddsValue)) {
                            System.out.printf("UPDATE: %-25s | Previous: %7.2f | Current: %7.2f | Event: %s%n",
                                    currentRunner.getName(), previousOddsValue, currentOddsValue, currentRunner.getEvent());
                        }
                    }
                }
            }

            // Save the newly fetched odds to a file and update the main currentOdds map.
            saveOdds(latestOdds);
            this.currentOdds = latestOdds; // Update current odds for API
        } catch (Exception e) {
            System.err.println("Error during odds polling: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Map<Long, MyRunner> getOdds() {
        System.out.println("Fetching current odds from Betfair...");
        Map<Long, MyRunner> horses = new HashMap<>();
        try {
            DataFetcher fetcher = new DataFetcher();
            Session session = fetcher.getSession();
            session.login();// Use the authenticator to log in
            System.out.println("Logged in with session status: " + session.getStatus());
            if ("SUCCESS".equals(session.getStatus())) {
                horses = fetcher.getData(LocalDate.now().plusDays(0).format(DateTimeFormatter.ISO_LOCAL_DATE));
                System.out.println("Fetched " + horses.size() + " runners.");
                session.logout();
            } else {
                System.err.println("Login failed with status: " + session.getStatus() + ". Aborting operation.");
            }
        } catch (Exception e) {
            System.err.println("An unexpected error occurred in the main process:");
            e.printStackTrace();
        } 
        System.out.println("Fetched " + horses.size() + " runners.");
        return horses;
    }

    public Map<Long, MyRunner> getCurrentOdds() {
        return this.currentOdds;
    }

    public Map<Long, MyRunner> getPreviousOdds() {
        return this.previousOdds;
    }

    public Map<Long, MyRunner> getInitialOdds() {
        return this.initialOdds;
    }

    public Map<Long, MyRunner> getLastKnownOdds() {
        return this.lastKnownOdds;
    }

    public Map<Long, Double> getLastRecordedMovementMap() {
        return this.lastRecordedMovementMap;
    }

    public RaceHistoryPayload getRaceHistory(String eventIdentifier) {
        List<File> snapshotFiles = getSnapshotFiles();
        // A map to hold the history for each runner in the race. Key: runnerId, Value: RunnerHistory object.
        Map<Long, RunnerHistory> raceHistories = new HashMap<>();
    
        DateTimeFormatter parser = DateTimeFormatter.ofPattern("'odds_'yyyyMMdd_HHmmss'.ser'");
    
        for (File file : snapshotFiles) {
            try {
                String filename = file.getName();
                LocalDateTime timestamp = LocalDateTime.parse(filename, parser);
    
                Map<Long, MyRunner> snapshot = loadOddsFromFile(file);
    
                for (Map.Entry<Long, MyRunner> entry : snapshot.entrySet()) {
                    MyRunner runner = entry.getValue();
                    // Check if this runner belongs to the requested event
                    if (runner != null && eventIdentifier.equals(runner.getEvent())) {
                        long runnerId = entry.getKey();
                        
                        // If it's a valid odds point, add it to the runner's history
                        if (runner.getOdds() != null && runner.getOdds() > 0) {
                            // Get or create the history object for this runner
                            RunnerHistory runnerHistory = raceHistories.computeIfAbsent(
                                runnerId, 
                                id -> new RunnerHistory(id, runner.getName(), new ArrayList<>())
                            );
                            
                            // Add the new data point
                            runnerHistory.history().add(new HistoryPoint(timestamp, runner.getOdds()));
                            
                            // Update name in case it changes (unlikely but safe)
                            if (!runnerHistory.runnerName().equals(runner.getName())) {
                                raceHistories.put(runnerId, new RunnerHistory(runnerId, runner.getName(), runnerHistory.history()));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Could not parse history from file: " + file.getName());
            }
        }
    
        return new RaceHistoryPayload(eventIdentifier, new ArrayList<>(raceHistories.values()));
    }

    /**
     * A helper method to find, filter, and sort all snapshot files chronologically.
     * This encapsulates the repetitive file-handling logic.
     * @return A sorted List of snapshot files, or an empty list if none are found.
     */
    private List<File> getSnapshotFiles() {
        File dir = new File(SNAPSHOT_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Snapshot directory not found.");
            return Collections.emptyList();
        }

        File[] files = dir.listFiles((d, name) -> name.startsWith("odds_") && name.endsWith(".ser"));
        if (files == null || files.length == 0) {
            System.out.println("No snapshot files found.");
            return Collections.emptyList();
        }

        List<File> fileList = new ArrayList<>(Arrays.asList(files));
        // Sort files chronologically (oldest to newest) based on filename
        fileList.sort(java.util.Comparator.comparing(File::getName));
        return fileList;
    }

    private Map<Long, MyRunner> loadPreviousOdds(List<File> snapshotFiles) {
        if (snapshotFiles.isEmpty()) {
            System.out.println("No previous odds files found. This is the first run.");
            return new HashMap<>();
        }

        // The list is sorted chronologically, so the last file is the most recent.
        File fileToLoad = snapshotFiles.get(snapshotFiles.size() - 1);
        System.out.println("Loading most recent odds from: " + fileToLoad.getName());
        return loadOddsFromFile(fileToLoad);
    }

    private Map<Long, MyRunner> loadInitialOdds(List<File> snapshotFiles) {
        if (snapshotFiles.isEmpty()) {
            System.out.println("No initial odds files found. No historical baseline.");
            return new HashMap<>();
        }

        // The list is sorted chronologically, so the first file is the earliest.
        File fileToLoad = snapshotFiles.get(0);
        System.out.println("Loading initial baseline odds from: " + fileToLoad.getName());
        return loadOddsFromFile(fileToLoad);
    }

    private Map<Long, MyRunner> loadLastKnownOdds(List<File> snapshotFiles) {
        System.out.println("Building last known odds from all snapshots...");
        Map<Long, MyRunner> lastOdds = new HashMap<>();

        if (snapshotFiles.isEmpty()) {
            return lastOdds;
        }

        for (File file : snapshotFiles) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Map<Long, MyRunner> snapshotOdds = (Map<Long, MyRunner>) ois.readObject();
                lastOdds.putAll(snapshotOdds); // Overwrite with newer odds
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Error loading snapshot " + file.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("Finished building last known odds for " + lastOdds.size() + " runners.");
        return lastOdds;
    }

    private Map<Long, Double> buildLastRecordedMovementMap(List<File> snapshotFiles) {
        System.out.println("Building last recorded movement map from all snapshots...");
        Map<Long, Double> lastMovements = new HashMap<>();
        Map<Long, MyRunner> previousSnapshotOdds = new HashMap<>();
        
        if (snapshotFiles.isEmpty()) {
            return lastMovements;
        }

        for (File file : snapshotFiles) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                Map<Long, MyRunner> currentSnapshotOdds = (Map<Long, MyRunner>) ois.readObject();
                for (Map.Entry<Long, MyRunner> entry : currentSnapshotOdds.entrySet()) {
                    MyRunner previousRunner = previousSnapshotOdds.get(entry.getKey());
                    MyRunner currentRunner = entry.getValue();
                    if (previousRunner != null && previousRunner.getOdds() != null && currentRunner.getOdds() != null && !currentRunner.getOdds().equals(previousRunner.getOdds())) {
                        lastMovements.put(entry.getKey(), currentRunner.getOdds() - previousRunner.getOdds());
                    }
                }
                previousSnapshotOdds.putAll(currentSnapshotOdds); // Merge current into previous for the next iteration
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Error processing snapshot for movement map " + file.getName() + ": " + e.getMessage());
            }
        }
        System.out.println("Finished building last recorded movement map.");
        return lastMovements;
    }

    private Map<Long, MyRunner> loadOddsFromFile(File file) {
        if (file == null) return new HashMap<>();
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (Map<Long, MyRunner>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error loading odds from " + file.getName() + ": " + e.getMessage());
        }
        return new HashMap<>();
    }


    private void saveOdds(Map<Long, MyRunner> oddsToSave) {
        new File(SNAPSHOT_DIR).mkdirs(); // Ensure the directory exists
        String filename = FILENAME_FORMATTER.format(LocalDateTime.now());
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(SNAPSHOT_DIR, filename)))) {
            oos.writeObject(oddsToSave);
            System.out.println("Odds snapshot saved to " + filename);
        } catch (IOException e) {
            System.err.println("Error saving odds: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        OddsTracker poller = new OddsTracker();
        WebServer webServer = new WebServer(poller);

        webServer.start(); // Start the web server
        poller.startPolling();

        System.out.println("Web server started on http://localhost:8080");
    }
}
