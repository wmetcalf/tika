#!/bin/bash
# Skip tika-grpc module which requires OS detection extension.
# Use the Maven wrapper (./mvnw, currently 3.9.12): after the upstream sync the
# build enforces Maven >= 3.9, which system `mvn` on many dev machines predates.
./mvnw install -e -DskipTests -pl '!tika-grpc'
