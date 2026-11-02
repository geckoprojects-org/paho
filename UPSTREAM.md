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
- **Inbound packets are limited to 1 MiB** (#24). A larger packet closes the connection with
  reason code 32204 (v3) or 51001 (v5) and a warning in the log. Raise the limit with
  `MqttConnectOptions.setMaxInboundPacketSize` (v3; `0` for none) or
  `MqttConnectionOptions.setMaximumPacketSize` (v5; `null` for none). v5 announces the limit in
  CONNECT, so the broker drops larger messages instead of sending them. v3 cannot announce it: a
  broker that keeps such a message for the client sends it again after every reconnect.
- **v5: `maximumPacketSize` limits what the client receives, the server's limit what it sends**
  (#24). Upstream checked outgoing packets against the client's own option and inbound packets
  against the server's.
- **The WebSocket handshake requires a `Connection: Upgrade` header** (#25), as RFC 6455 does;
  upstream's check was inverted and only passed because the value kept its leading space.
- **The file persistence directory is owner-only** (#27). Applications that read it with another
  user need an explicit persistence directory and their own permissions.
- **`generateClientId()` returns `paho` + 16 hex digits** (#30) instead of `paho` + a time stamp.

## Planned

| Issue | Upstream | Clients | Summary |
|---|---|---|---|
| [#23](https://github.com/geckoprojects-org/paho/issues/23) | – | v3, v5 | Use the application's `ScheduledExecutorService` for the reconnect timer (and the v3 pings) instead of a thread per client. |

## Security fixes

From a review of both clients for CWE weaknesses, with secure by default and by design as the yardstick. All were in code taken over unchanged from upstream.

| Issue | Upstream (related) | Clients | CWE | Summary |
|---|---|---|---|---|
| [#24](https://github.com/geckoprojects-org/paho/issues/24) | [#820](https://github.com/eclipse-paho/paho.mqtt.java/issues/820), [#462](https://github.com/eclipse-paho/paho.mqtt.java/issues/462) | v3, v5 | CWE-789, CWE-770, CWE-20 | Inbound packets are limited to 1 MiB by default and checked before the buffer is allocated. Before, one forged length allocated 256 MiB, and the `OutOfMemoryError` ended the receiver thread without reconnect. In v5 the incoming and outgoing limits were also swapped. Variable byte integers longer than 4 bytes are rejected. |
| [#25](https://github.com/geckoprojects-org/paho/issues/25) | [#501](https://github.com/eclipse-paho/paho.mqtt.java/issues/501), [#1000](https://github.com/eclipse-paho/paho.mqtt.java/issues/1000) | v3, v5 | CWE-789, CWE-190, CWE-476, CWE-835 | WebSocket frames are bounded by the same limit, the 64-bit length no longer overflows, end of stream is an `IOException`. The handshake response is bounded (16 KiB) and read without read-ahead. |
| [#26](https://github.com/geckoprojects-org/paho/issues/26) | [#922](https://github.com/eclipse-paho/paho.mqtt.java/issues/922) | v5 (v3: debug dump) | CWE-532 | The CONNECT password and authentication data no longer appear in the FINE log, store passwords no longer in the debug dump of the connect options. |
| [#27](https://github.com/geckoprojects-org/paho/issues/27) | – | v3, v5 | CWE-276, CWE-312 | The client directory of the file persistence is restricted to its owner (`rwx------`). |
| [#28](https://github.com/geckoprojects-org/paho/issues/28) | – | v3, v5 | CWE-261, CWE-327 | `SSLSocketFactoryFactory.obfuscate` is deprecated and documented as encoding only; the Javadoc points to `setSocketFactory` for real secrets. |
| [#29](https://github.com/geckoprojects-org/paho/issues/29) | – | v3, v5 | CWE-319, CWE-523 | One warning per client when a password (or `ws://` user info) goes to a non-loopback host without TLS. |
| [#30](https://github.com/geckoprojects-org/paho/issues/30) | – | v3 | CWE-330, CWE-340 | `generateClientId()` uses 64 bits from a `SecureRandom` instead of `System.nanoTime()`. |

None of them adds a thread, a timer or a dependency. Checked without finding: TLS hostname verification is on by default (`HTTPS` endpoint identification, SNI), the default protocol is the JDK's `TLS` with its disabled legacy versions, WebSocket masking keys come from `SecureRandom`, and the file persistence directory name cannot leave the data directory (`isSafeChar`).

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
