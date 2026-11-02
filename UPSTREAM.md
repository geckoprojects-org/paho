# Differences from upstream Eclipse Paho

This build is based on [eclipse-paho/paho.mqtt.java](https://github.com/eclipse-paho/paho.mqtt.java)
`develop` (`bd291f15`, the state of the last upstream release 1.2.5 plus later fixes). Every
difference has an issue here; the commit that resolves it closes the issue. Fix commits touch only
client sources and tests, one topic per commit, so they can be cherry-picked to upstream as they
are.

## Fixed here

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#1](https://github.com/geckoprojects-org/paho/issues/1) | [#511](https://github.com/eclipse-paho/paho.mqtt.java/issues/511) | v3, v5 | A connect attempt without CONNACK now times out after `connectionTimeout`; before, it hung in `CONNECTING` and automatic reconnect stopped for good. |
| [#2](https://github.com/geckoprojects-org/paho/issues/2) | – | v3, v5 | The automatic reconnect state (`reconnectDelay`, `clientLock`) is kept per client instead of in static fields shared by all clients of a JVM. |
| [#3](https://github.com/geckoprojects-org/paho/issues/3) | – | v3, v5 | The reconnect cycle reschedules when `connect()` fails synchronously instead of ending silently. |
| [#4](https://github.com/geckoprojects-org/paho/issues/4) | [#493](https://github.com/eclipse-paho/paho.mqtt.java/issues/493) | v3, v5 | New option `automaticResubscribe` (default `true`): subscriptions are restored after an automatic reconnect without session. |
| [#5](https://github.com/geckoprojects-org/paho/issues/5) | – | v3, v5 | Broker host names with `_` work on Java 16+; the reflection on `java.net.URI` that failed there is no longer needed. |
| [#6](https://github.com/geckoprojects-org/paho/issues/6) | [#857](https://github.com/eclipse-paho/paho.mqtt.java/issues/857) | v5 | The OSGi bundle exports the API packages (`common`, `common.packet`, …), not only `org.eclipse.paho.mqttv5.client`. |
| [#7](https://github.com/geckoprojects-org/paho/issues/7) | [#425](https://github.com/eclipse-paho/paho.mqtt.java/issues/425), [#654](https://github.com/eclipse-paho/paho.mqtt.java/issues/654) | v3, v5 | `disconnect()` and `close()` end the automatic reconnect cycle: no reconnect after `disconnect()`, no reconnect thread after `close()`, no `NullPointerException` from `reconnect()` before `connect()`. |
| [#8](https://github.com/geckoprojects-org/paho/issues/8) | [#958](https://github.com/eclipse-paho/paho.mqtt.java/issues/958) | v3, v5 | Keep-alive pings and the reconnect delay are scheduled on the monotonic clock; a time jump back no longer delays them. Same thread count as before. |
| [#9](https://github.com/geckoprojects-org/paho/issues/9) | [#1065](https://github.com/eclipse-paho/paho.mqtt.java/issues/1065) | v3 | A callback waiting for a client operation (e.g. a QoS 1 publish in `messageArrived`) no longer deadlocks the shutdown on connection loss, so the client reconnects. v5 is not affected. |
| [#10](https://github.com/geckoprojects-org/paho/issues/10) | [#551](https://github.com/eclipse-paho/paho.mqtt.java/issues/551) | v3, v5 | The in-flight slot of a QoS 1/2 publish is freed before the waiting publisher resumes; sequential publishes no longer get "Too many publishes in progress". |
| [#11](https://github.com/geckoprojects-org/paho/issues/11) | [#828](https://github.com/eclipse-paho/paho.mqtt.java/issues/828) | v3, v5 | A QoS 1/2 message that arrives without callback or message listener (e.g. from a persistent session before `subscribe`) is acknowledged and discarded with a warning, instead of being dropped without PUBACK and redelivered on every connect. |

## Behaviour changes for applications

What existing application code may notice when switching from upstream 1.2.5:

- **`automaticResubscribe` is on by default** (#4). After an automatic reconnect without session
  the client re-subscribes on its own. An application that also re-subscribes in `connectComplete`
  subscribes twice, which is harmless at the broker; set the option to `false` to keep doing it
  yourself.
- **`connectionTimeout` also bounds the wait for the CONNACK** (#1). A connect attempt to a server
  that accepts the TCP connection but never answers fails after the timeout instead of hanging.
  `0` keeps waiting indefinitely, as before.
- **`disconnect()` and `close()` end the automatic reconnect cycle** (#7). `reconnect()` without a
  previous `connect()` throws `REASON_CODE_CLIENT_NOT_CONNECTED` instead of a
  `NullPointerException`.
- **A message without callback or message listener is acknowledged and discarded** (#11), with a
  warning in the log, also with manual acknowledgements. Set the `MqttCallback` before `connect()`
  so that messages of a persistent session are not discarded.
- **Pings and reconnect delays follow the monotonic clock** (#8). Same threads as before.

## Planned

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#23](https://github.com/geckoprojects-org/paho/issues/23) | – | v3, v5 | Use the application's `ScheduledExecutorService` for the reconnect timer (and the v3 pings) instead of a thread per client. |

## Security findings

From a review of both clients for CWE weaknesses, with secure by default and by design as the yardstick. All are in code taken over unchanged from upstream; none is fixed yet.

| Issue | Upstream (related) | Clients | CWE | Summary |
|---|---|---|---|---|
| [#24](https://github.com/geckoprojects-org/paho/issues/24) | [#820](https://github.com/eclipse-paho/paho.mqtt.java/issues/820), [#462](https://github.com/eclipse-paho/paho.mqtt.java/issues/462) | v3, v5 | CWE-789, CWE-770, CWE-20 | Inbound packets are not size-limited by default; v5 allocates before checking `maximumPacketSize`; variable byte integers longer than 4 bytes are accepted. One forged length allocates 256 MiB, and the resulting `OutOfMemoryError` ends the receiver thread without reconnect. |
| [#25](https://github.com/geckoprojects-org/paho/issues/25) | [#501](https://github.com/eclipse-paho/paho.mqtt.java/issues/501), [#1000](https://github.com/eclipse-paho/paho.mqtt.java/issues/1000) | v3, v5 | CWE-789, CWE-190, CWE-476 | WebSocket: the 64-bit frame length overflows an `int`, frames and handshake headers are unbounded, EOF in the handshake ends in an NPE. |
| [#26](https://github.com/geckoprojects-org/paho/issues/26) | [#922](https://github.com/eclipse-paho/paho.mqtt.java/issues/922) | v5 | CWE-532 | `MqttConnect.toString()` contains the password and is logged at FINE for every CONNECT; the connection options' debug dump shows key store passwords. |
| [#27](https://github.com/geckoprojects-org/paho/issues/27) | – | v3, v5 | CWE-276, CWE-312 | The default constructors persist QoS 1/2 messages in clear to `user.dir`, with umask permissions. |
| [#28](https://github.com/geckoprojects-org/paho/issues/28) | – | v3, v5 | CWE-261, CWE-327 | `{xor}` key store passwords look protected but are only encoded. |
| [#29](https://github.com/geckoprojects-org/paho/issues/29) | – | v3, v5 | CWE-319, CWE-523 | Credentials and WebSocket Basic authentication go over `tcp://`/`ws://` without any notice. |
| [#30](https://github.com/geckoprojects-org/paho/issues/30) | – | v3 | CWE-330, CWE-340 | `generateClientId()` is `"paho" + System.nanoTime()`, a guessable session identity. |

Checked without finding: TLS hostname verification is on by default (`HTTPS` endpoint identification, SNI), the default protocol is the JDK's `TLS` with its disabled legacy versions, WebSocket masking keys come from `SecureRandom`, and the file persistence directory name cannot leave the data directory (`isSafeChar`).

## Checked, no change needed

- [#12](https://github.com/geckoprojects-org/paho/issues/12), upstream
  [#1098](https://github.com/eclipse-paho/paho.mqtt.java/issues/1098) and
  [#1099](https://github.com/eclipse-paho/paho.mqtt.java/issues/1099) (several clients in one JVM:
  TCP without CONNECT or a second CONNECT after a broker restart): not reproduced with up to 50
  clients, broker restarts, persistent sessions, slow consumers and the 3.1.1 → 3.1 fallback. The
  only candidate in the code would no longer leave a client hanging, since #1 and #3.
- Upstream [#816](https://github.com/eclipse-paho/paho.mqtt.java/issues/816) and
  [#826](https://github.com/eclipse-paho/paho.mqtt.java/issues/826) (v5 `subscribe`) are already
  fixed on upstream `develop`, which this build is based on.
- Upstream [#507](https://github.com/eclipse-paho/paho.mqtt.java/issues/507),
  [#694](https://github.com/eclipse-paho/paho.mqtt.java/issues/694),
  [#794](https://github.com/eclipse-paho/paho.mqtt.java/issues/794) (`FileLock` reflection): the file
  persistence opens on JDK 11, 17 and 25; the reflected `tryLock()` is declared in the exported
  `java.nio.channels.FileChannel`.

## Build and packaging

Not upstream contributions, but different from an upstream build:

- bnd workspace build (Gradle 9, JDK 25, Java 8 bytecode) instead of Maven/Tycho; Maven coordinates
  `org.geckoprojects.paho:org.eclipse.paho.client.mqttv3` / `org.eclipse.paho.mqttv5.client`,
  version line 1.3.x.
- Snapshots (`1.3.0-SNAPSHOT`) are published by Jenkins from the branch `snapshot` to the DIM Nexus
  and the Maven Central snapshot repository; releases from `main` to the DIM Nexus and Maven
  Central.
- Tests that need no broker run in every build, against an in-process `FakeMqttBroker` that can
  go silent, refuse connections or drop them; their waits scale with `-Ppaho.test.timeFactor`
  (CI: 2). The upstream broker tests run with `./gradlew brokerTest` against a Mosquitto 2 test
  broker in a container, with renewed test certificates (the upstream ones expired in 2018); see
  the README.
- Upstream modules that are not built here (samples, trace formatter, p2 repositories) are removed.

## Contributing back

Upstream Paho Java has been inactive since 2022 (last release 1.2.5, July 2020). Should it become
active again, each fix commit referenced by a closed issue above can be offered as a pull request
by cherry-picking it onto upstream `develop`.
