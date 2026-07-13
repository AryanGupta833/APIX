# APIX

> AI-powered API Debugging & Observability Platform built with Spring Boot, Spring AI, OpenTelemetry, and Jaeger.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-Tracing-blue)
![Jaeger](https://img.shields.io/badge/Jaeger-Distributed%20Tracing-orange)
![License](https://img.shields.io/badge/License-MIT-green)

---

# Overview

APIX is a developer-focused API debugging and observability platform that combines API execution, distributed tracing, AI-assisted debugging, and performance analysis into a single application.

Instead of switching between Postman, logs, tracing tools, and AI assistants, APIX provides a unified debugging experience capable of executing requests, analyzing failures, visualizing distributed traces, and recommending fixes using Large Language Models (LLMs).

The platform is designed to simplify debugging across microservices by providing end-to-end visibility into every API request.

---

# Features

## API Execution Engine

Execute HTTP APIs with support for

- GET
- POST
- PUT
- PATCH
- DELETE

Capture

- Response Status
- Headers
- Response Body
- Response Time
- Request Metadata

---

## AI-Powered Root Cause Analysis

APIX integrates Spring AI with LLMs to automatically analyze failed requests.

The AI assistant can

- Explain API failures
- Detect probable root causes
- Recommend debugging steps
- Suggest possible fixes

Example

```
401 Unauthorized

↓

Possible Cause

Missing Authorization Header

↓

Suggested Fix

Include a valid Bearer JWT token.
```

---

## Distributed Tracing

Integrated with OpenTelemetry and Jaeger.

Visualize

- Request lifecycle
- Service dependencies
- Span hierarchy
- End-to-end latency
- Distributed request flow

---

## X-Ray Diagnostics

Automatically detects failures across multiple networking layers

- DNS Resolution
- TLS Handshake
- Authentication
- API Gateway
- Downstream Services
- Backend Errors

---

## Performance Analysis

Analyze request performance using

- Total latency
- Server processing time
- Response size
- Bottleneck detection
- Latency breakdown

---

## Request History

Every request is automatically stored.

Supports

- Request replay
- Execution history
- Saved Collections
- Debugging sessions

---

## Load Testing

Analyze API performance under load.

Provides

- Average latency
- Peak latency
- Throughput
- Error rate

---

# System Architecture

```
               Client
                  │
                  ▼
          API Execution Engine
                  │
        ┌─────────┴──────────┐
        ▼                    ▼
 OpenTelemetry         AI Debugger
        │                    │
        ▼                    ▼
    Jaeger UI        Spring AI + LLM
        │                    │
        └─────────┬──────────┘
                  ▼
         Performance Analyzer
                  │
                  ▼
         Request History Store
```

---

# Tech Stack

### Backend

- Java
- Spring Boot
- Spring AI
- Reactor Netty
- WebClient

### Observability

- OpenTelemetry
- Jaeger

### AI

- OpenAI API
- Spring AI

### Build Tool

- Maven

---

# Core Modules

## API Execution Engine

Responsible for

- Request execution
- Response parsing
- Latency measurement
- Metadata collection

---

## AI Debugging Engine

Uses LLMs to

- Analyze failures
- Explain errors
- Recommend fixes
- Improve developer productivity

---

## Tracing Engine

Built using OpenTelemetry.

Generates spans for every API request and exports them to Jaeger for visualization.

---

## Diagnostics Engine

Performs protocol-level diagnostics across

- DNS
- TLS
- Authentication
- Gateway
- Backend Services

---

## Performance Analyzer

Calculates

- Average Response Time
- Bottleneck Identification
- Latency Distribution

---

# Example Workflow

```
Execute API
      │
      ▼
Capture Response
      │
      ▼
Generate Trace
      │
      ▼
Analyze Performance
      │
      ▼
AI Root Cause Analysis
      │
      ▼
Suggested Fixes
```

---

# REST APIs

## Execute Request

```
POST /api/execute
```

---

## Request History

```
GET /api/history
```

---

## Saved Collections

```
POST /api/collections

GET /api/collections
```

---

## AI Analysis

```
POST /api/analyze
```

---

## Tracing

```
GET /api/traces
```

---

# Performance Highlights

- AI-assisted debugging
- End-to-end distributed tracing
- Automatic failure analysis
- Request replay
- Latency breakdown
- Bottleneck detection
- Load testing support
- 1000+ API requests analyzed

---

# Roadmap

## Completed

- API Execution Engine
- Request History
- Collections
- AI Root Cause Analysis
- OpenTelemetry Integration
- Jaeger Tracing
- Performance Analytics
- X-Ray Diagnostics

---

## Planned

- GraphQL Support
- WebSocket Testing
- gRPC Support
- OAuth 2.0 Authentication
- API Mock Server
- Team Workspaces
- CI/CD Integration
- Browser Extension
- Desktop Client

---

# Getting Started

```bash
git clone https://github.com/AryanGupta833/APIX.git

cd APIX

mvn spring-boot:run
```

---

# Future Vision

APIX aims to become a unified developer platform for API testing, distributed tracing, observability, and AI-assisted debugging, reducing the time developers spend diagnosing production issues while improving overall developer productivity.

---

# Author

**Aryan Gupta**

GitHub:
https://github.com/AryanGupta833

LinkedIn:
https://linkedin.com/in/aryan-gupta-316a35366
