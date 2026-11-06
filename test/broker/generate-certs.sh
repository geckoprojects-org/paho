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
# Generates the test PKI of the broker tests: a test CA, the broker certificate for
# localhost/127.0.0.1, a "man-in-the-middle" certificate for another host name, a client
# certificate, and the clientkeystore.jks (password "password") of both test modules, which holds
# the CA as trusted entry and the client key.
#
# Test material only. The output is committed so the tests run without this step; run it again
# when the certificates expire (validity: DAYS below).
set -e
DAYS=7300
cd "$(dirname "$0")"
out=certs
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"

openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -subj "/CN=Paho Test CA" \
	-addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
	-keyout "$tmp/ca.key" -out "$out/ca.crt"

sign() { # name subject subjectAltName extendedKeyUsage
	openssl req -newkey rsa:2048 -nodes -subj "$2" -keyout "$out/$1.key" -out "$tmp/$1.csr"
	printf 'subjectAltName=%s\nextendedKeyUsage=%s\nbasicConstraints=CA:FALSE\n' "$3" "$4" > "$tmp/$1.ext"
	openssl x509 -req -in "$tmp/$1.csr" -CA "$out/ca.crt" -CAkey "$tmp/ca.key" -CAcreateserial \
		-days "$DAYS" -extfile "$tmp/$1.ext" -out "$out/$1.crt"
}
sign server "/CN=localhost" "DNS:localhost,IP:127.0.0.1" serverAuth
sign server-mitm "/CN=mitm.invalid" "DNS:mitm.invalid" serverAuth
sign client "/CN=paho-test-client" "DNS:paho-test-client" clientAuth
# mosquitto runs as an unprivileged user in the container
chmod 644 "$out"/*.key

openssl pkcs12 -export -name client -inkey "$out/client.key" -in "$out/client.crt" \
	-certfile "$out/ca.crt" -passout pass:password -out "$tmp/client.p12"
store="$tmp/clientkeystore.jks"
keytool -importkeystore -noprompt -srckeystore "$tmp/client.p12" -srcstoretype PKCS12 \
	-srcstorepass password -destkeystore "$store" -deststoretype JKS -deststorepass password
keytool -importcert -noprompt -alias paho-test-ca -file "$out/ca.crt" \
	-keystore "$store" -storepass password
for module in org.eclipse.paho.client.mqttv3.test org.eclipse.paho.mqttv5.client.test; do
	cp "$store" "../../$module/src/test/resources/clientkeystore.jks"
done
rm -f "$out/ca.srl" "$out"/*.csr
echo "Generated $(ls "$out" | tr '\n' ' ')and clientkeystore.jks"
