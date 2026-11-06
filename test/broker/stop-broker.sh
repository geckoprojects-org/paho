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
# Stops the broker started by test/broker/start-broker.sh.
ENGINE=$(command -v docker || command -v podman) || { echo "docker or podman required" >&2; exit 1; }
"$ENGINE" rm -f paho-test-broker >/dev/null 2>&1 && echo "paho-test-broker stopped"
