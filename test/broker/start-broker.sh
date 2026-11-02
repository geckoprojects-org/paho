#!/bin/sh
################################################################################
# Copyright (c) 2026 Data In Motion Consulting GmbH.
#
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Eclipse Public License v2.0
# and Eclipse Distribution License v1.0 which accompany this distribution.
#
# The Eclipse Public License is available at
#    https://www.eclipse.org/legal/epl-2.0
# and the Eclipse Distribution License is available at
#   https://www.eclipse.org/org/documents/edl-v10.php
#
# Contributors:
#    Mark Hoffmann - initial API and implementation
################################################################################
# Starts the Mosquitto broker for the Paho broker tests in a container (docker or podman):
#   test/broker/start-broker.sh        then   ./gradlew brokerTest
#   test/broker/stop-broker.sh
# Listeners: see test/broker/mosquitto.conf.
set -e
IMAGE=${PAHO_TEST_BROKER_IMAGE:-docker.io/library/eclipse-mosquitto:2.0.20}
NAME=paho-test-broker
cd "$(dirname "$0")"
ENGINE=$(command -v docker || command -v podman) || { echo "docker or podman required" >&2; exit 1; }

"$ENGINE" rm -f "$NAME" >/dev/null 2>&1 || true
"$ENGINE" run -d --name "$NAME" \
	-p 1883:1883 -p 18883:18883 -p 8080:8080 -p 8883:8883 \
	-p 18884:18884 -p 18885:18885 -p 18886:18886 -p 18887:18887 \
	-v "$(pwd)/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" \
	-v "$(pwd)/certs:/mosquitto/config/certs:ro" \
	"$IMAGE" >/dev/null

# Wait for mosquitto's start message; a port check is not enough, since podman's port forwarder
# accepts connections even when the broker has already exited.
i=0
until "$ENGINE" logs "$NAME" 2>&1 | grep -q ' running$'; do
	i=$((i + 1))
	if [ "$i" -ge 30 ] || [ "$("$ENGINE" inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null)" != "true" ]; then
		"$ENGINE" logs "$NAME" >&2
		echo "broker did not start" >&2
		exit 1
	fi
	sleep 1
done
echo "$NAME running ($IMAGE)"
