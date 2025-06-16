# Spring Backend in Kafka

## Author
Aldo

## Overview
This directory contains the Spring Boot backend services for the Omero platform.

## Structure
```
backend-spring-chatbot/
├── charts/
│   └── spring-client/
│       ├── Chart.yaml
│       └── templates/
└── secrets/
    └── spring-client-secrets.yaml
```

## Components

### Spring API
- REST API endpoints
- Business logic
- Database integration
- Service orchestration

### Spring Auth
- Authentication service
- JWT token management
- User management
- Role-based access control

### Spring Config
- Configuration management
- Environment-based configs
- Config refresh
- Config encryption

### Spring Gateway
- API Gateway
- Route management
- Request filtering
- Load balancing

## Setup

### Prerequisites
- Java 17+
- Maven
- Spring Boot CLI
- Helm
- kubectl
- SOPS

### Installation
1. Run `setup_backend_spring_kafka.sh` from setup_scripts directory for Spring backend setup
2. Configure secrets with setup_scripts/*.yaml
3. Deploy services using Helm
4. Configure database connections
5. Set up monitoring and logging

## Security

- All secrets are encrypted using SOPS
- RBAC enabled
- Network policies enforced
- TLS enabled for all services
- Rate limiting
- Request validation

## License

MIT
