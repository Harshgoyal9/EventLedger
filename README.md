# Event Ledger

A distributed financial transaction processing system composed of two microservices that handle event ingestion, idempotency, out-of-order event processing, and account balance management.

## Architecture Overview

```
┌──────────────────────┐
│   Browser / Client   │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  Event Gateway API   │  Port 8080
│   (public-facing)    │
│   - H2 Database      │
│   - Circuit Breaker  │
└──────────┬───────────┘
           │ REST (sync)
           ▼
┌──────────────────────┐
│   Account Service    │  Port 8081
│     (internal)       │
│   - H2 Database      │
└──────────────────────┘
```

### Event Gateway API (Port 8080)
The public-facing entry point for all client requests:
- Receives and validates transaction events
- Enforces idempotency (duplicate event detection)
- Stores event records in its own H2 database
- Forwards transactions to Account Service
- Implements circuit breaker for resilience

### Account Service (Port 8081)
Internal service for account state management:
- Manages account balances and transaction history
- Applies credits and debits to accounts
- Uses its own separate H2 database
- Not exposed to external clients

## API Endpoints

### Event Gateway API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account (chronological order) |
| `GET` | `/accounts/{accountId}/balance` | Get account balance (proxied to Account Service) |
| `GET` | `/health` | Health check with dependency status |
| `GET` | `/metrics/custom` | Custom metrics endpoint |

### Account Service API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction |
| `GET` | `/accounts/{accountId}/balance` | Get account balance |
| `GET` | `/accounts/{accountId}` | Get account details |
| `GET` | `/health` | Health check |

## Event Payload

```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": {
    "source": "mainframe-batch",
    "batchId": "B-9042"
  }
}
```

## Prerequisites

- **Java 17** or higher
- **Maven 3.8+** 
- **Docker & Docker Compose** (optional, for containerized deployment)

## Setup Instructions

### Option 1: Running with Docker Compose (Recommended)

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd event-ledger
   ```

2. Build and start both services:
   ```bash
   docker-compose up --build
   ```

3. Services will be available at:
   - Gateway: http://localhost:8080
   - Account Service: http://localhost:8081

### Option 2: Running Manually

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd event-ledger
   ```

2. Build the project:
   ```bash
   mvn clean install -DskipTests
   ```

3. Start Account Service (in terminal 1):
   ```bash
   cd account-service
   mvn spring-boot:run
   ```

4. Start Gateway Service (in terminal 2):
   ```bash
   cd gateway-service
   mvn spring-boot:run
   ```

5. Services will be available at:
   - Gateway: http://localhost:8080
   - Account Service: http://localhost:8081

## Running Tests

```bash
# Run all tests
mvn test

# Run tests for a specific module
mvn test -pl account-service
mvn test -pl gateway-service
```

## Key Features

### 1. Idempotency
- Duplicate events (same `eventId`) are detected and not processed twice
- Duplicate submissions return the original event with HTTP 200 status
- New events return HTTP 201 status

### 2. Out-of-Order Tolerance
- Events may arrive out of chronological order
- Event listings are always returned in chronological order by `eventTimestamp`
- Balance is always correct regardless of arrival order

### 3. Distributed Tracing
- OpenTelemetry integration for trace propagation
- Trace ID generated at Gateway for each request
- Propagated via `X-Trace-Id` header to Account Service
- JSON-structured logs include trace ID for correlation

### 4. Observability
- **Structured Logging**: JSON-formatted logs with trace ID, timestamp, level, service name
- **Health Checks**: `/health` endpoints with database and dependency status
- **Custom Metrics**: Request counts, processing times, error rates

## Resiliency Pattern: Circuit Breaker

The Gateway implements the **Circuit Breaker** pattern for calls to the Account Service using Resilience4j.

### Why Circuit Breaker?

I chose the circuit breaker pattern because it provides:

1. **Fail Fast**: When the Account Service is repeatedly failing, the circuit breaker "opens" and immediately rejects requests without waiting for timeouts, improving response time for clients.

2. **Self-Healing**: The circuit breaker automatically attempts recovery after a configurable wait period (30 seconds), allowing the system to resume normal operation when the Account Service recovers.

3. **Load Reduction**: By stopping calls to a failing service, we prevent overwhelming it with requests during recovery, giving it time to heal.

4. **Visibility**: The circuit breaker state is exposed in the health endpoint, making it easy to monitor system health.

### Configuration

```
- Failure Rate Threshold: 50%
- Wait Duration in Open State: 30 seconds
- Sliding Window Size: 10 calls
- Minimum Number of Calls: 5
```

### Circuit Breaker States

- **CLOSED**: Normal operation, calls pass through
- **OPEN**: Failures exceeded threshold, calls are rejected immediately (503 response)
- **HALF_OPEN**: After wait duration, limited calls allowed to test recovery

## Graceful Degradation

When the Account Service is unavailable:

| Endpoint | Behavior |
|----------|----------|
| `POST /events` | Returns 503 Service Unavailable |
| `GET /events/{id}` | **Works** - uses Gateway's local database |
| `GET /events?account=...` | **Works** - uses Gateway's local database |
| `GET /accounts/{id}/balance` | Returns 503 with clear error message |

## Example Requests

### Submit an Event
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z"
  }'
```

### Get Event by ID
```bash
curl http://localhost:8080/events/evt-001
```

### Get Events for Account
```bash
curl "http://localhost:8080/events?account=acct-123"
```

### Check Health
```bash
curl http://localhost:8080/health
```

## Project Structure

```
event-ledger/
├── pom.xml                    # Parent POM
├── docker-compose.yml         # Docker orchestration
├── README.md
├── account-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/eventledger/account/
│       │   ├── controller/    # REST controllers
│       │   ├── service/       # Business logic
│       │   ├── repository/    # Data access
│       │   ├── entity/        # JPA entities
│       │   ├── dto/           # Request/Response DTOs
│       │   ├── config/        # Configuration classes
│       │   ├── filter/        # Tracing filter
│       │   └── metrics/       # Custom metrics
│       └── main/resources/
│           └── application.yml
└── gateway-service/
    ├── pom.xml
    ├── Dockerfile
    └── src/
        ├── main/java/com/eventledger/gateway/
        │   ├── controller/    # REST controllers
        │   ├── service/       # Business logic
        │   ├── repository/    # Data access
        │   ├── entity/        # JPA entities
        │   ├── dto/           # Request/Response DTOs
        │   ├── client/        # Account Service client
        │   ├── config/        # Configuration classes
        │   ├── filter/        # Tracing filter
        │   └── metrics/       # Custom metrics
        └── main/resources/
            └── application.yml
```

## Technology Stack

- **Java 17**
- **Spring Boot 3.2.5**
- **Spring Data JPA** - Data persistence
- **H2 Database** - In-memory database (per service)
- **OpenTelemetry** - Distributed tracing
- **Resilience4j** - Circuit breaker
- **Micrometer** - Metrics
- **Logstash Logback Encoder** - JSON logging
- **Docker** - Containerization
- **Maven** - Build tool
- **JUnit 5 & MockMvc** - Testing

## License

This project is created as part of a technical assessment.
