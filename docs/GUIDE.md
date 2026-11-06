# Paho Java Client — Guide

A complete guide to the Gecko build of the Eclipse Paho Java client: how to add it, connect,
publish and subscribe, which options matter, and how to set it up for a desktop or server
application and for an embedded device. What differs from upstream Paho, and why, is in
[UPSTREAM.md](UPSTREAM.md); building and testing this repository is in the [README](../README.md).

- [1. Choosing a client](#1-choosing-a-client)
- [2. Adding the client](#2-adding-the-client)
- [3. First steps](#3-first-steps)
- [4. Connection options](#4-connection-options)
- [5. Reliable delivery](#5-reliable-delivery)
- [6. Transports: TCP, TLS, WebSocket](#6-transports-tcp-tls-websocket)
- [7. Threads and the shared executor](#7-threads-and-the-shared-executor)
- [8. Resource use](#8-resource-use)
- [9. Setup: desktop and server applications](#9-setup-desktop-and-server-applications)
- [10. Setup: embedded devices](#10-setup-embedded-devices)
- [11. OSGi](#11-osgi)
- [12. Errors and logging](#12-errors-and-logging)
- [13. Migrating from upstream Paho 1.2.5](#13-migrating-from-upstream-paho-125)

## 1. Choosing a client

| | MQTT 3.1 / 3.1.1 | MQTT 5 |
|---|---|---|
| Package | `org.eclipse.paho.client.mqttv3` | `org.eclipse.paho.mqttv5.client` (+ `org.eclipse.paho.mqttv5.common`) |
| Options class | `MqttConnectOptions` | `MqttConnectionOptions` |
| Callback | `MqttCallback`, `MqttCallbackExtended` | `MqttCallback` |
| Broker support | every MQTT broker | MQTT 5 brokers (Mosquitto 2, HiveMQ, EMQX, …) |

Both have the same structure: `MqttAsyncClient` is fully asynchronous, every operation returns a
token you can wait on or attach a listener to; `MqttClient` is a blocking wrapper around it. Use
MQTT 5 when the broker supports it: reason codes tell you why something failed, the session expiry
is explicit, and the client announces its packet size limit to the broker. Both need Java 8 or
newer and have no dependencies.

## 2. Adding the client

From Maven Central:

```xml
<dependency>
    <groupId>org.geckoprojects.paho</groupId>
    <artifactId>org.eclipse.paho.mqttv5.client</artifactId>   <!-- or org.eclipse.paho.client.mqttv3 -->
    <version>1.3.0</version>
</dependency>
```

```groovy
implementation 'org.geckoprojects.paho:org.eclipse.paho.mqttv5.client:1.3.0'
```

Packages and class names are those of upstream Paho, so the jars replace the upstream ones without
code changes. Exclude `org.eclipse.paho:*` when another dependency brings the upstream client
along, both contain the same classes.

## 3. First steps

### MQTT 5

```java
import java.nio.charset.StandardCharsets;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

public class V5Example {

    public static void main(String[] args) throws Exception {
        MqttAsyncClient client = new MqttAsyncClient("tcp://localhost:1883", "sensor-42",
                new MemoryPersistence());
        client.setCallback(new MqttCallback() {
            public void connectComplete(boolean reconnect, String serverURI) {
                System.out.println((reconnect ? "reconnected to " : "connected to ") + serverURI);
            }

            public void disconnected(MqttDisconnectResponse response) {
                System.out.println("disconnected: " + response.getReasonString());
            }

            public void messageArrived(String topic, MqttMessage message) {
                System.out.println(topic + ": " + new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            public void deliveryComplete(IMqttToken token) {
            }

            public void mqttErrorOccurred(MqttException exception) {
                exception.printStackTrace();
            }

            public void authPacketArrived(int reasonCode, MqttProperties properties) {
            }
        });

        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setAutomaticReconnect(true);
        client.connect(options).waitForCompletion(10000);

        client.subscribe("commands/sensor-42", 1).waitForCompletion(10000);
        client.publish("telemetry/sensor-42", "21.5".getBytes(StandardCharsets.UTF_8), 1, false)
                .waitForCompletion(10000);

        client.disconnect().waitForCompletion(10000);
        client.close();
    }
}
```

### MQTT 3.1.1

```java
import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class V3Example {

    public static void main(String[] args) throws Exception {
        MqttAsyncClient client = new MqttAsyncClient("tcp://localhost:1883", "sensor-42",
                new MemoryPersistence());
        client.setCallback(new MqttCallbackExtended() {
            public void connectComplete(boolean reconnect, String serverURI) {
                System.out.println((reconnect ? "reconnected to " : "connected to ") + serverURI);
            }

            public void connectionLost(Throwable cause) {
                System.out.println("connection lost: " + cause);
            }

            public void messageArrived(String topic, MqttMessage message) {
                System.out.println(topic + ": " + new String(message.getPayload(), StandardCharsets.UTF_8));
            }

            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        client.connect(options).waitForCompletion(10000);

        client.subscribe("commands/sensor-42", 1).waitForCompletion(10000);
        client.publish("telemetry/sensor-42", "21.5".getBytes(StandardCharsets.UTF_8), 1, false)
                .waitForCompletion(10000);

        client.disconnect().waitForCompletion(10000);
        client.close();
    }
}
```

Rules that save trouble:

- **Set the callback before `connect()`.** Messages of a persistent session arrive right after the
  CONNACK; without a callback or listener they are acknowledged and discarded with a warning (#11).
- **Do not block in `messageArrived`.** It runs on the client's callback thread; while it blocks,
  no other message and no completion is delivered. Hand longer work to your own executor.
- **Subscriptions survive an automatic reconnect** (`automaticResubscribe`, default `true`, #4).
  Do not subscribe again in `connectComplete`, unless you turn the option off.
- **Client IDs must be unique per broker.** A second client with the same ID makes the broker drop
  the first one, and both reconnect in turn. `MqttAsyncClient.generateClientId()` returns
  `paho` + 16 random hex digits.
- **`close()` after `disconnect()`** releases the persistence and the threads; a closed client
  cannot be used again.

## 4. Connection options

The options that matter most, with their defaults. v3 names are on `MqttConnectOptions`, v5 names
on `MqttConnectionOptions`; where only one is given, it is the same in both.

| Option | Default | Meaning |
|---|---|---|
| `setKeepAliveInterval` | 60 s | Ping interval while nothing else is sent. Also the only wakeup of an idle client (#45). `0` disables pings. |
| `setConnectionTimeout` | 30 s | Bounds the TCP connect, the TLS handshake and the wait for the CONNACK (#1). `0` waits forever. |
| `setAutomaticReconnect` | `false` | Reconnect after a connection loss, with a growing delay. |
| v3 `setMaxReconnectDelay` | 128 000 ms | Upper bound of the reconnect delay (v3). |
| v5 `setAutomaticReconnectDelay(min, max)` | 1 s, 120 s | First and longest reconnect delay (v5). |
| `setAutomaticResubscribe` | `true` | Restore subscriptions after an automatic reconnect without session (#4). |
| v3 `setCleanSession` / v5 `setCleanStart` | `true` | `false` keeps the session (subscriptions, unacknowledged QoS 1/2 messages) across connections. |
| v5 `setSessionExpiryInterval` | `null` (0: session ends with the connection) | How long the broker keeps the session after a disconnect, in seconds. |
| v3 `setMaxInflight` | 10 | QoS 1/2 publishes sent but not yet acknowledged. v5 takes the server's Receive Maximum. |
| v5 `setReceiveMaximum` | `null` (65 535) | QoS 1/2 messages the broker may send before the client acknowledges them. |
| `setMaxQueuedMessages` | 10 000 | Publishes waiting to be written while the link stalls; beyond that `publish()` fails with `MAX_INFLIGHT` (#46). `0` for no limit. |
| v3 `setMaxInboundPacketSize` / v5 `setMaximumPacketSize` | 1 MiB | Largest packet the client accepts; checked before memory is allocated (#24). v3 `0` / v5 `null` for no limit (256 MiB). |
| `setSocketFactory` | plain or JDK default TLS | Your `SSLContext`'s socket factory for TLS, see [6](#6-transports-tcp-tls-websocket). |
| `setHttpsHostnameVerificationEnabled` | `true` | Check that the server certificate matches the host name. |
| `setUserName`, `setPassword` | – | Credentials; a warning is logged once per client when they go to a remote host without TLS (#29). |
| `setWill` | – | Message the broker publishes when the client disappears without DISCONNECT. |

Offline buffering is set on the client, not in the options: `client.setBufferOpts(...)`, see
[5](#5-reliable-delivery).

## 5. Reliable delivery

### QoS and sessions

| QoS | Delivery | Cost per message (TCP, measured) |
|---|---|---|
| 0 | at most once, nothing is stored | 1 write |
| 1 | at least once, duplicates possible | 1 write, 1 read for the PUBACK |
| 2 | exactly once | 2 writes, 2 reads |

For QoS 1/2 across a connection loss the session must survive: v3 `cleanSession=false`, v5
`cleanStart=false` plus a `sessionExpiryInterval`. Then the broker keeps the subscriptions and the
messages that arrive while the client is away, and the client resends its unacknowledged
publishes after reconnecting.

### Persistence

The persistence holds the client's unacknowledged QoS 1/2 messages:

- `MemoryPersistence`: lost when the process ends. No disk I/O.
- `MqttDefaultFilePersistence(directory)`: survives a restart. One file per message, written and
  synced (`fsync`) before the message is sent, deleted when it is acknowledged. The directory is
  owner-only (#27) and locked, so a second process with the same client ID fails to open it.

The single-argument constructor `new MqttAsyncClient(uri, clientId)` uses file persistence in the
working directory; pass a persistence explicitly to choose.

### Offline buffer

While the client is disconnected (with automatic reconnect), `publish()` fails unless the offline
buffer is enabled:

```java
DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
buffer.setBufferEnabled(true);
buffer.setBufferSize(1000);           // messages; default 5000
buffer.setDeleteOldestMessages(true); // full buffer: drop the oldest instead of refusing the new one
buffer.setPersistBuffer(false);       // true: also store buffered messages in the persistence
client.setBufferOpts(buffer);
```

A full buffer either drops the oldest message (`deleteOldestMessages=true`) or refuses the new one
with `REASON_CODE_DISCONNECTED_BUFFER_FULL` (32203). Either way the message ID and the stored copy
are released (#39). After the reconnect the buffer is sent in order, as fast as the in-flight
window allows.

### Flow control while connected

A publish waits in a queue until the sender thread writes it. On a stalled link (peer does not
read, full TCP window) the queue grows until keep-alive declares the connection dead, about
1.5 × keep-alive. `maxQueuedMessages` bounds it (#46): beyond the limit `publish()` throws
`MqttException` with `REASON_CODE_MAX_INFLIGHT` (32202), as for a full in-flight window. Treat it
as back-pressure: retry later or drop the value.

```java
try {
    client.publish(topic, payload, 0, false);
} catch (MqttException e) {
    if (e.getReasonCode() == MqttClientException.REASON_CODE_MAX_INFLIGHT) { // v3: MqttException.REASON_CODE_MAX_INFLIGHT
        // link is behind: drop this sample or retry later
    } else {
        throw e;
    }
}
```

## 6. Transports: TCP, TLS, WebSocket

| URI | Transport |
|---|---|
| `tcp://host:1883` | plain TCP |
| `ssl://host:8883` | TLS |
| `ws://host:8080/mqtt` | WebSocket |
| `wss://host:8081/mqtt` | WebSocket over TLS |

### TLS

Pass the socket factory of your own `SSLContext`; it decides about trust and client
certificates:

```java
KeyStore trust = KeyStore.getInstance("PKCS12");
try (InputStream in = new FileInputStream("/etc/myapp/ca.p12")) {
    trust.load(in, trustPassword);
}
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trust);
SSLContext context = SSLContext.getInstance("TLS");
context.init(null, tmf.getTrustManagers(), null);   // key managers for a client certificate

options.setSocketFactory(context.getSocketFactory());
```

- Keep hostname verification on (`httpsHostnameVerificationEnabled`, default `true`); a
  certificate for another name is refused.
- **Reuse one `SSLContext`** for all connections of a client: its session cache lets a reconnect
  resume the TLS session (one round trip and no certificate check instead of a full handshake).
  This works with TLS 1.2 and 1.3 after `disconnect()`, a forced disconnect and a connection loss,
  tested on JDK 11 to 25 (`TlsSessionResumptionTest`). A new `SSLContext` per connect costs a full
  handshake every time, which is expensive on a small CPU.
- `setSSLProperties` (`com.ibm.ssl.*` keys) still works; for secrets prefer your own
  `SSLContext`, the `obfuscate` helper is an encoding, not encryption (#28).

### WebSocket

For brokers reachable only through HTTP infrastructure. Pings and fragmented frames are handled
(#31), and frames are bounded by the inbound packet limit (#25). `setCustomWebSocketHeaders` adds
headers to the handshake. WebSocket costs more than TCP: one more thread per connection and a
wakeup per second while idle (its receiver keeps the 1 s read timeout). Prefer `tcp://` or
`ssl://` where you can.

## 7. Threads and the shared executor

Without an executor, each connected client runs four threads: receiver, sender, callback and a
ping timer ("MQTT Ping"). During a reconnect a timer thread ("MQTT Reconnect") waits for the next
attempt. `close()` ends all of them.

With an application `ScheduledExecutorService`, the client runs on it instead (#23): receiver,
sender and callback as long-running tasks (three threads per connected client), the keep-alive
pings and the reconnect delay as scheduled tasks. No own timer threads.

```java
ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(3 * clients + 2);

// the same constructor in v3 and v5; null as ping sender runs the pings on the executor
MqttAsyncClient client = new MqttAsyncClient(uri, clientId, new MemoryPersistence(), null, executor);
```

- Size the pool for **three threads per connected client plus a few** for the scheduled tasks; a
  pool that is too small lets clients wait for a thread that never comes free.
- The executor belongs to the application: `close()` leaves it running, shut it down yourself.
- In v3, the three-argument constructor uses the timer ping sender even with an executor;
  use the five-argument one with `null` as above.

## 8. Resource use

Measured with the load test of this repository (`./gradlew loadTest`, see README):

| | per connected client |
|---|---|
| Threads | 4 (3 with a shared executor) |
| Heap | about 25 KiB plus the messages in flight |
| Read buffer | 512 bytes (#35) |
| System calls per message | QoS 0: 1 write; QoS 1: 1 write + 1 read; QoS 2: 2 writes + 2 reads |
| Idle | no wakeups between keep-alive pings over TCP/TLS (#45); WebSocket: one per second |

The soak scenarios run rounds of publish, subscribe, unsubscribe and reconnect and found no growth
of heap, threads, file descriptors, message IDs or stored files over the rounds. Limits that keep a
misbehaving peer from exhausting memory: inbound packet size (1 MiB), send queue (10 000
publishes), offline buffer (5 000 messages), in-flight window.

## 9. Setup: desktop and server applications

Plenty of memory, a reliable network, restarts are rare. Typical goals: no message loss, fast
recovery, TLS with the operating system's trust store.

```java
MqttConnectionOptions options = new MqttConnectionOptions();
options.setAutomaticReconnect(true);
options.setAutomaticReconnectDelay(1, 60);
options.setCleanStart(false);
options.setSessionExpiryInterval(24L * 3600);   // the broker keeps the session for a day
options.setKeepAliveInterval(30);
options.setUserName("app");
options.setPassword(password);                  // byte[]
options.setSocketFactory(SSLContext.getDefault().getSocketFactory()); // JDK trust store

Path data = Paths.get(System.getProperty("user.home"), ".myapp", "mqtt");
MqttAsyncClient client = new MqttAsyncClient("ssl://broker.example.com:8883", "myapp-" + hostName,
        new MqttDefaultFilePersistence(data.toString()));

DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
buffer.setBufferEnabled(true);
buffer.setBufferSize(10000);
buffer.setPersistBuffer(true);   // buffered messages survive a restart
client.setBufferOpts(buffer);

client.setCallback(callback);    // before connect
client.connect(options);
```

- File persistence in a per-user directory keeps unacknowledged QoS 1/2 messages across restarts.
- Several clients in one application: share one `ScheduledThreadPoolExecutor` (section 7) to
  avoid four threads per client.
- Large payloads (files, images): raise `maximumPacketSize` (v5) or `maxInboundPacketSize` (v3)
  on purpose, the 1 MiB default is a safety limit.
- Stop cleanly on shutdown: `disconnect()` then `close()`, so that the broker does not publish the
  will message and the persistence lock is released.

## 10. Setup: embedded devices

Small heap, little CPU, flash storage that wears out, a link that comes and goes, often a battery.
Typical goals: bounded memory, few threads, few wakeups, few writes to flash.

```java
MqttConnectionOptions options = new MqttConnectionOptions();
options.setAutomaticReconnect(true);
options.setAutomaticReconnectDelay(5, 300);     // back off to 5 minutes on a dead link
options.setKeepAliveInterval(240);              // fewer wakeups; below the NAT timeout of the network
options.setCleanStart(false);
options.setSessionExpiryInterval(3600L);
options.setMaximumPacketSize(64L * 1024);       // commands are small; refuse anything larger
options.setReceiveMaximum(10);                  // at most 10 unacknowledged inbound QoS 1/2 messages
options.setMaxQueuedMessages(100);              // back-pressure instead of a growing queue
options.setSocketFactory(sslContext.getSocketFactory()); // one SSLContext for the process lifetime

MqttAsyncClient client = new MqttAsyncClient("ssl://broker.example.com:8883", deviceId,
        new MemoryPersistence());                // no flash writes per message

DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
buffer.setBufferEnabled(true);
buffer.setBufferSize(200);                      // bounded: about 200 telemetry samples
buffer.setDeleteOldestMessages(true);           // keep the newest values
buffer.setPersistBuffer(false);
client.setBufferOpts(buffer);
```

For an MQTT 3.1.1 broker the same with `MqttConnectOptions`: `setMaxInboundPacketSize(64 * 1024)`,
`setMaxInflight(10)`, `setMaxQueuedMessages(100)`, `setMaxReconnectDelay(300000)`,
`setCleanSession(false)`.

What to decide and why:

- **Persistence.** `MemoryPersistence` writes nothing to flash; unacknowledged QoS 1/2 messages
  are lost on a reboot, the broker still holds the session. `MqttDefaultFilePersistence` writes and
  syncs one file per QoS 1/2 message; use it only for messages that must survive a reboot, and keep
  it on a storage that tolerates the writes. The same holds for `persistBuffer`.
- **QoS.** Telemetry that is replaced by the next sample: QoS 0 (one write, nothing stored).
  Commands and events that must arrive: QoS 1. QoS 2 doubles the round trips.
- **Keep-alive.** Between pings an idle TCP/TLS client does not wake up (#45), so the keep-alive
  interval sets the idle cost. Longer saves battery and data; it must stay below the idle timeout
  of NAT gateways and mobile networks (often 5 minutes or more), or the connection dies silently
  and is only noticed after 1.5 × keep-alive.
- **Memory bounds.** Every queue is bounded: inbound packets (`maximumPacketSize`), inbound
  in-flight (`receiveMaximum`), send queue (`maxQueuedMessages`), offline buffer (`bufferSize`).
  Pick them from the heap you can spend, e.g. `bufferSize × typical payload` for the buffer.
- **Threads.** One client needs four threads. Reduce their stack with `-Xss256k` (or a
  `ThreadFactory` with a stack size in a shared executor) if memory is tight. Several clients:
  share one executor.
- **Transport.** `ssl://` over `wss://`: no extra thread and no wakeup per second. Reuse one
  `SSLContext`, so that reconnects resume the TLS session instead of a full handshake.
- **Reconnect.** A long maximum delay (`automaticReconnectDelay(5, 300)`) avoids a radio that never
  sleeps on a dead link. The cycle ends on `disconnect()`/`close()` (#7) and is not confused by
  clock changes, common on devices that set their time late (#8).
- **Clock.** Pings and reconnect delays use the monotonic clock (#8); an NTP correction after boot
  does not delay them.
- **Logging.** Keep `java.util.logging` at INFO or above for `org.eclipse.paho`; FINE logs every
  packet.

## 11. OSGi

Both jars are OSGi bundles with the symbolic names `org.eclipse.paho.client.mqttv3` and
`org.eclipse.paho.mqttv5.client`. They export their API packages (v5 also `common`,
`common.packet`, …, #6) and import nothing beyond the JRE. Install the bundle and use the client as
a plain Java library from your component; create the client in `@Activate`, `disconnect()` and
`close()` it in `@Deactivate`. The built-in transports (TCP, TLS, WebSocket) are found inside the
bundle through `ServiceLoader` (`NetworkModuleFactory`), as upstream.

## 12. Errors and logging

Failures come as `MqttException` (v3) or `MqttException`/`MqttClientException` reason codes (v5),
from the call or on the token (`token.getException()`).

| Code | Constant | Meaning |
|---|---|---|
| 32202 | `REASON_CODE_MAX_INFLIGHT` | In-flight window full or send queue at `maxQueuedMessages` (#46): back off. |
| 32203 | `REASON_CODE_DISCONNECTED_BUFFER_FULL` | Offline buffer full and `deleteOldestMessages=false`. |
| 32204 (v3), 51001 (v5) | `REASON_CODE_INCOMING_PACKET_TOO_LARGE` | Inbound packet above the limit; the connection is closed (v5 sends DISCONNECT 0x95 first, #33). |
| 32000 | `REASON_CODE_CLIENT_TIMEOUT` | No answer in time, e.g. no CONNACK within `connectionTimeout` (#1). |
| 32104 | `REASON_CODE_CLIENT_NOT_CONNECTED` | Operation while disconnected (and no offline buffer). |
| 32109 | `REASON_CODE_CONNECTION_LOST` | The connection broke; automatic reconnect takes over if enabled. |

The client logs through `java.util.logging` under the logger names `org.eclipse.paho.client.mqttv3`
and `org.eclipse.paho.mqttv5.client`. WARNING and SEVERE report problems (too large packets,
discarded messages, credentials without TLS); FINE traces every packet. Bridge it with
`jul-to-slf4j` if your application uses SLF4J.

## 13. Migrating from upstream Paho 1.2.5

Replace the dependency (section 2); no code changes are needed. Check the defaults that differ,
listed in [UPSTREAM.md, Behaviour changes](UPSTREAM.md#behaviour-changes-for-applications), in
particular:

- `automaticResubscribe` is on: remove your own re-subscribe in `connectComplete`, or turn it off.
- Inbound packets are limited to 1 MiB: raise the limit if you receive larger payloads.
- At most 10 000 publishes wait to be written: a publisher that outruns a stalled link gets
  `MAX_INFLIGHT` instead of a growing heap.
- `connectionTimeout` also bounds the wait for the CONNACK.
- The file persistence directory is owner-only.
