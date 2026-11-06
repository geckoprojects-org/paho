#!/bin/bash
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
# Runs the tests that need no broker repeatedly while every core is busy (issue #22), and lists
# each failed test per run. No tools beyond bash and the Gradle wrapper.
#
#   test/stress-tests.sh [runs] [timeFactor]     defaults: 3 runs, factor 2 (as CI)
#
# With factor 2 one run takes about 2.5 min without load and 3.5 min under load on 16 cores, so
# the default takes about 10 min. PAHO_STRESS_LOAD=0 runs without the busy loops.
RUNS=${1:-3}
FACTOR=${2:-2}
cd "$(dirname "$0")/.." || exit 1
pids=()
trap 'kill "${pids[@]}" 2>/dev/null' EXIT
if [ "${PAHO_STRESS_LOAD:-1}" != 0 ]; then
	for _ in $(seq "$(nproc)"); do ( while :; do :; done ) & pids+=($!); done
fi
echo "runs=$RUNS factor=$FACTOR busy loops=${#pids[@]}"
failed=0
for run in $(seq "$RUNS"); do
	start=$(date +%s)
	./gradlew -q -Ppaho.test.timeFactor="$FACTOR" \
		:org.eclipse.paho.client.mqttv3.test:cleanTest :org.eclipse.paho.client.mqttv3.test:test \
		:org.eclipse.paho.mqttv5.client.test:cleanTest :org.eclipse.paho.mqttv5.client.test:test \
		--continue > /dev/null 2>&1
	rc=$?
	echo "run $run: rc=$rc, $(( $(date +%s) - start )) s"
	if [ $rc != 0 ]; then
		failed=$((failed + 1))
		grep -l '<failure\|<error' org.eclipse.paho.*.test/generated/test-reports/test/TEST-*.xml 2>/dev/null \
			| sed 's|.*/TEST-\(.*\)\.xml|  failed in \1|'
	fi
done
echo "$failed of $RUNS runs failed"
[ $failed = 0 ]
