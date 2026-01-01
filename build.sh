#!/bin/bash
# Skip tika-grpc module which requires OS detection extension
mvn install -e -DskipTests -pl '!tika-grpc'
