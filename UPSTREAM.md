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
| [#31](https://github.com/geckoprojects-org/paho/issues/31) | [#960](https://github.com/eclipse-paho/paho.mqtt.java/issues/960), [#1000](https://github.com/eclipse-paho/paho.mqtt.java/issues/1000) | v3, v5 | The WebSocket transport answers pings with pongs, ignores unsolicited pongs and reassembles fragmented messages (RFC 6455); before, each of them ended the connection, e.g. the idle ping of Mosquitto 2 after about five minutes. The receiver thread writes the pong; all frames go out under one lock. |
| [#23](https://github.com/geckoprojects-org/paho/issues/23) | – | v3, v5 | With an application `ScheduledExecutorService`, the reconnect delay (v3, v5) and the default keep-alive pings (v3, `pingSender` `null`) run on it instead of a timer thread per client; `close()` leaves the executor running. Without an executor nothing changes. |
| [#33](https://github.com/geckoprojects-org/paho/issues/33) | – | v5 | When an incoming packet exceeds the Maximum Packet Size, the client sends DISCONNECT with reason code 0x95 (Packet too large) before it closes the connection, as MQTT 5.0 (4.13) recommends; before, it closed the socket without telling the server why. Written on the existing sender, at most 1 s wait, failures ignored. |
| [#35](https://github.com/geckoprojects-org/paho/issues/35) | – | v3, v5 | The network stream is read through a 512-byte buffer per connection; before, every header byte was a system call of its own (4 reads for a PUBACK, 17 per echoed QoS 2 message). Larger bodies are still read directly into the packet. |
| [#36](https://github.com/geckoprojects-org/paho/issues/36) | – | v5 | The inbound QoS 2 flow (PUBCOMP created, also on manual acknowledgement) is logged at FINE instead of INFO, the default level of `java.util.logging`; before, every received QoS 2 message wrote a line to the application's log. |
| [#37](https://github.com/geckoprojects-org/paho/issues/37) | – | v3, v5 | Stopping a network module closes the socket also when a step before fails: v5 `shutdownInput()` on an `SSLSocket` on Java 8, the WebSocket close frame on a broken connection. Before, the file descriptor stayed open until garbage collection. |
| [#38](https://github.com/geckoprojects-org/paho/issues/38) | – | v5 | A subscribe of a filter that is subscribed already replaces its message listener and subscription identifier; before, every re-subscribe kept the old listener. Removing listeners no longer throws `ConcurrentModificationException` and removes the right subscription identifier; the listener maps are guarded by a lock. |
| [#39](https://github.com/geckoprojects-org/paho/issues/39) | – | v3, v5 | Messages that leave the offline buffer without being sent (discarded as the oldest, refused because it is full) release their message ID and stored copy, and a buffered message refused with `MAX_INFLIGHT` keeps its ID. Before, each case leaked a message ID (with `cleanSession=false` until all 65,535 were used up) and, in v5, the stored message. |
| [#40](https://github.com/geckoprojects-org/paho/issues/40) | – | v3 | Sending the offline buffer no longer busy-spins on a full in-flight window (with `maxInflight` 3 or less forever); the drain loop's `MAX_INFLIGHT` handling waits instead. |
| [#41](https://github.com/geckoprojects-org/paho/issues/41) | – | v3, v5 | An automatic reconnect attempt that succeeds after `disconnect()` was called is disconnected again; before, the client could stay connected and a following `close()` failed. |
| [#42](https://github.com/geckoprojects-org/paho/issues/42) | – | v3 | `removeMessage()` removes a publish that is still queued; before, it was never found and sent later with a released message ID, and the in-flight count was decremented for it. |
| [#43](https://github.com/geckoprojects-org/paho/issues/43) | – | v3, v5 | The file persistence closes its streams when writing or reading fails (e.g. a full disk) and the lock file when locking fails (e.g. a second client with the same ID); `get()` no longer loops forever on a truncated file. Before, each failure leaked a file descriptor. |
| [#44](https://github.com/geckoprojects-org/paho/issues/44) | – | v5 | The outgoing topic alias counter stops at the server's maximum; before, it grew with every publish to a topic without alias and, after 2^31 of them on one connection, produced invalid negative aliases. |
| [#47](https://github.com/geckoprojects-org/paho/issues/47) | – | v3, v5 | QoS 0 messages restored from the stored offline buffer are forgotten once sent; before, they were queued again on every reconnect and held an in-flight slot each. |
| [#49](https://github.com/geckoprojects-org/paho/issues/49) | – | v3, v5 | The WebSocket receiver writes a frame's payload to the pipe in one call instead of byte by byte (7 times faster), and the masking keys come from one shared `SecureRandom` instead of a new one per frame. |
| [#50](https://github.com/geckoprojects-org/paho/issues/50) | – | v5 | An incoming PUBLISH that carries only a topic alias is resolved to its topic; before, it was delivered on the empty topic (and the lookup could never succeed). |
| [#46](https://github.com/geckoprojects-org/paho/issues/46) | – | v3, v5 | New option `maxQueuedMessages` (default 10,000, 0 for no limit): publishes waiting to be written are limited, a further one fails with `MAX_INFLIGHT`. Before, the in-flight check counted only the written publishes, so while the link stalled the queue grew until keep-alive gave up, for QoS 0 without any other bound. |
| [#51](https://github.com/geckoprojects-org/paho/issues/51) | – | v5 | Outgoing topic aliases are applied when the publish is written, and the stored message keeps its topic. Before, a QoS 1/2 publish resent after a reconnect carried only an alias of the old connection; the server closes such a connection, so the client never got a working connection again while the message was in the session. |

Found with the soak scenarios of the load test (#34) and a review for resource leaks, the rows from
#35 on are about what grows on a device that runs for months: file descriptors, message IDs,
listeners, stored messages, in-flight slots, system calls and CPU per message.

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
- **An application executor also runs the client's timers** (#23): the reconnect delay and, with
  the default ping sender, the keep-alive pings. The executor needs a thread for them besides the
  receiver, sender and callback tasks of its clients. In v3, a `null` pingSender with an executor
  no longer throws a `NullPointerException`.
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
  v5 sends DISCONNECT 0x95 before it closes the connection (#33); as before, the broker then
  publishes the will message.
- **v5: `maximumPacketSize` limits what the client receives, the server's limit what it sends**
  (#24). Upstream checked outgoing packets against the client's own option and inbound packets
  against the server's.
- **The WebSocket handshake requires a `Connection: Upgrade` header** (#25), as RFC 6455 does;
  upstream's check was inverted and only passed because the value kept its leading space.
- **The file persistence directory is owner-only** (#27). Applications that read it with another
  user need an explicit persistence directory and their own permissions.
- **`generateClientId()` returns `paho` + 16 hex digits** (#30) instead of `paho` + a time stamp.
- **v5 logs received QoS 2 messages at FINE** (#36); an application that relied on the INFO line
  per message has to enable FINE for `org.eclipse.paho.mqttv5.client.internal`.
- **A subscribe while offline with `persistBuffer=true` is buffered** (#39); v3 threw a
  `ClassCastException` before, since it tried to store it as a publish. Only publishes are stored.
- **A reconnect that outlives `disconnect()` is disconnected again** (#41): an application sees a
  `connectComplete` followed by the disconnect in this rare case, instead of a connected client.
- **v5 assigns topic aliases per connection when writing** (#51): the token's message keeps its
  topic; nothing changes for applications that do not set aliases themselves.
- **At most 10,000 publishes wait to be written** (#46, option `maxQueuedMessages`): beyond that,
  `publish()` fails with `MAX_INFLIGHT`, which it so far did only for a full in-flight window. A
  working connection does not queue that many; a stalled one did, until keep-alive closed it.

## Open, need a decision

| Issue | Clients | Summary |
|---|---|---|
| [#45](https://github.com/geckoprojects-org/paho/issues/45) | v3, v5 | An idle client wakes up every second (receiver read timeout of 1 s, a `SocketTimeoutException` each time). Raising it needs a change of the shutdown order (close the socket, then join the receiver). |

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
  (CI: 2). Under full CPU load they are stable with factor 1 and 2 (#22: 40 runs measured, the
  two races found fixed); `test/stress-tests.sh` repeats the check, about 10 min for 3 runs. The
  upstream broker tests run with `./gradlew brokerTest` against a Mosquitto 2 test
  broker in a container, with renewed test certificates (the upstream ones expired in 2018); see
  the README.
- A load test of both clients (`org.eclipse.paho.loadtest`, `./gradlew loadTest`) against the
  same broker, on request only (#32), with soak scenarios that fail when heap, threads, file
  descriptors or persistence files grow over the rounds (#34): about 5.5 min with the default
  sizes, about 1 min with `-Ppaho.load.quick`.
- Upstream modules that are not built here (samples, trace formatter, p2 repositories) are removed.

## Contributing back

Upstream Paho Java has been inactive since 2022 (last release 1.2.5, July 2020). Should it become
active again, each fix commit referenced by a closed issue above can be offered as a pull request
by cherry-picking it onto upstream `develop`.
