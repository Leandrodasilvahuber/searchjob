#!/usr/bin/env bash
# Sobe o SearchJob em http://localhost:8787
cd "$(dirname "$0")" && exec mvn -q spring-boot:run
