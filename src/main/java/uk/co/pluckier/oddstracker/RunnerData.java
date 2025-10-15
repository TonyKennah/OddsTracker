package uk.co.pluckier.oddstracker;

/**
 * A simple record to hold runner data for JSON serialization.
 * This acts as a Data Transfer Object (DTO) for the web frontend.
 */
public record RunnerData(String name, double odds, String event, double initialOdds) {
}