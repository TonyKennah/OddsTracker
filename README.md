# OddsTracker

OddsTracker is a Java application for tracking and analyzing sports betting odds. It provides tools to monitor odds changes from the Betfair win market, helping users to find value and make informed decisions.

## Building from Source

### Prerequisites

*   Java Development Kit (JDK) 11 or higher
*   Apache Maven

### Build Instructions

To build the project, run the following Maven command from the root directory of the project:

```sh
mvn clean package
```

This will compile the source code and create an executable JAR file in the `target` directory.

## Running the Application

After building the project, you can run the application with the following command:

```sh
java --add-opens java.base/java.lang=ALL-UNNAMED -jar target/odds-tracker-1.0-SNAPSHOT.jar
```