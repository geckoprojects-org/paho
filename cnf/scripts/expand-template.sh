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
# Expands the Maven resource filtering placeholders of upstream Paho source templates, for the
# bnd -generate step of org.eclipse.paho.client.mqttv3 (bnd runs system commands without a shell,
# so the work happens here).
#
# Usage: expand-template.sh <version> <build-level> <template-dir> <output-dir>
set -e
version="$1"
level="$2"
out="$(pwd)/$4"
cd "$3"
find . -name '*.java' | while read -r f; do
	mkdir -p "$out/$(dirname "$f")"
	sed -e "s/\${project.version}/$version/g" -e "s/\${build.level}/$level/g" "$f" > "$out/$f"
done
