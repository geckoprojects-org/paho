# Paho Java Client — Gecko build

This repository is a **Gecko projects build of the [Eclipse Paho Java client](https://github.com/eclipse-paho/paho.mqtt.java)**,
not an official Eclipse release. It carries the upstream history and adds:

- **Reconnect fixes** (v3, v5): a connect attempt without CONNACK times out
  ([upstream #511](https://github.com/eclipse-paho/paho.mqtt.java/issues/511)); the reconnect state
  is per client; the reconnect cycle no longer ends silently, stops on `disconnect()`/`close()` and
  is not delayed by clock jumps; a callback waiting for a client operation no longer deadlocks the
  shutdown (v3).
- **Delivery fixes** (v3, v5): sequential QoS 1/2 publishes no longer get "Too many publishes in
  progress"; a message without callback or listener is acknowledged instead of redelivered forever.
- **Shared executor** (v3, v5): with an application `ScheduledExecutorService`, the reconnect
  delay and the keep-alive pings run on it; no timer thread per client.
- **WebSocket** (v3, v5): pings are answered and fragmented messages reassembled; the idle ping
  of Mosquitto 2 no longer ends `ws://` connections.
- **Security fixes** (v3, v5): inbound packets and WebSocket frames are size-limited by default
  (1 MiB) and checked before allocation; no passwords in the log; owner-only file persistence;
  a warning for credentials without TLS; random client ids.
- **Java 16+:** broker host names with `_` work without reflection on `java.net.URI`.
- **Resubscribe after reconnect:** new option `automaticResubscribe` (default `true`).
- **Resource use for long-running devices** (v3, v5): no leaks of file descriptors, message IDs,
  listeners or stored messages; a bounded send queue (`maxQueuedMessages`); one read per packet;
  no wakeups of an idle client between keep-alive pings; checked by soak tests.
- **OSGi and build:** a bnd workspace build (Gradle 9, JDK 25, Java 8 bytecode) instead of
  Maven/Tycho, with manifests that export the API packages of both clients (upstream v5 exports
  only `org.eclipse.paho.mqttv5.client`).

Every difference from upstream is tracked as an issue here; [UPSTREAM.md](UPSTREAM.md) gives the
overview of what is fixed, what is open and what was checked.

The Paho Java client is an MQTT client library for the JVM. It provides two APIs: `MqttAsyncClient`,
fully asynchronous with completion via callbacks, and `MqttClient`, a synchronous wrapper around it.
Details per protocol version: [MQTTv3.md](MQTTv3.md), [MQTTv5.md](MQTTv5.md).

**[GUIDE.md](GUIDE.md) is the complete guide**: adding the client, first steps for v3 and v5, the
connection options with their defaults, reliable delivery, TLS and WebSocket, threads and the shared
executor, measured resource use, and setup examples for desktop/server applications and for
embedded devices.

## Using the client

The packages and bundle symbolic names stay `org.eclipse.paho.*`, so the bundles are drop-in
replacements for the upstream ones (same API; Java 8 or newer). Some defaults behave differently,
see [Behaviour changes](UPSTREAM.md#behaviour-changes-for-applications). They are published to
Maven Central under their own coordinates; until the 1.3.0 release, as `1.3.0-SNAPSHOT` in the
Central snapshot repository and the DIM Nexus:

| Client | Maven coordinates |
|---|---|
| MQTT 3.1 / 3.1.1 | `org.geckoprojects.paho:org.eclipse.paho.client.mqttv3:1.3.0` |
| MQTT 5 | `org.geckoprojects.paho:org.eclipse.paho.mqttv5.client:1.3.0` |

```xml
<dependency>
    <groupId>org.geckoprojects.paho</groupId>
    <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
    <version>1.3.0</version>
</dependency>
```

Exclude `org.eclipse.paho:*` should another dependency bring the upstream client along; both contain
the same classes.

## Building and testing

```
./gradlew build
```

builds both bundles (`<module>/generated/*.jar`) and runs the tests that need no broker. All other
tests run against a Mosquitto 2 broker in a container (docker or podman), with the test
certificates in `test/broker/certs`:

```
test/broker/start-broker.sh
./gradlew brokerTest
test/broker/stop-broker.sh
```

The load test (`org.eclipse.paho.loadtest`, issue #32) runs against the same broker, only on
request:

```
./gradlew loadTest -Ppaho.load.clients=20 -Ppaho.load.messages=500 -Ppaho.load.rounds=5
```

It covers throughput with QoS 1 and 2, a reconnect storm through a proxy that drops all
connections, and the same storm with one shared executor, for v3 and v5. The soak scenarios
(`V3SoakTest`, `V5SoakTest`, issue #34) repeat rounds of publish (QoS 0, 1, 2), subscribe and
unsubscribe of fresh topics with message listeners and a dropped connection, record heap after GC,
threads, open file descriptors and persistence files per round, and fail when they keep growing.
They also report system calls and bytes per message and the CPU time and system calls while idle
(Linux, from `/proc/self`). Per scenario it reports
duration, threads and heap per client to `org.eclipse.paho.loadtest/generated/loadtest-report.txt`.

Durations, measured on 16 cores (including Gradle start):

| Command | Sizes | Duration |
|---|---|---|
| `./gradlew loadTest -Ppaho.load.quick` | 5 clients, 100 messages, 1 round; soak 2 clients x 8 rounds | about 1 min |
| `./gradlew loadTest` | 20 clients, 500 messages, 5 rounds; soak 4 clients x 40 rounds | about 5.5 min |
| `./gradlew loadTest --tests '*LoadTest'` | without the soak scenarios | about 70 s (quick: 20 s) |

Sizes given with `-Ppaho.load.clients`, `.messages`, `.rounds`, `.soakClients`, `.soakRounds` or
`.soakMessages` override both defaults; the time
grows mainly with the rounds (each storm round waits for all clients to reconnect, about 1.5 s).

`test/stress-tests.sh [runs] [timeFactor]` runs the tests that need no broker repeatedly while
every core is kept busy and lists the failed tests per run (issue #22). With factor 2 one run
takes about 2.5 min without load and 3.5 min under load, so the default of 3 runs takes about
10 min; `test/stress-tests.sh 1` about 3.5 min. `PAHO_STRESS_LOAD=0` leaves out the load.

`test/broker/generate-certs.sh` renews the test certificates and the `clientkeystore.jks` of the test
modules. Snapshots and releases are built by Jenkins (`Jenkinsfile`); GitHub Actions checks licence
headers, build and tests.

## Upstream

Upstream is configured as remote `upstream`; fixes from here are offered there as well.

- Paho project: [https://www.eclipse.org/paho](https://www.eclipse.org/paho),
  [projects.eclipse.org/projects/iot.paho](https://projects.eclipse.org/projects/iot.paho)
- Upstream repository and issues: [github.com/eclipse-paho/paho.mqtt.java](https://github.com/eclipse-paho/paho.mqtt.java)
- Mailing list: [paho-dev](https://accounts.eclipse.org/mailing-list/paho-dev)
- Log and debug in the Java client: [wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client](https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client)

Licence: as upstream, EPL-2.0 or EDL-1.0 (see `LICENSE`, `epl-v20`, `edl-v10`, `NOTICE`).

## Getting Started

For asynchronous examples of both clients and setups for desktop and embedded use, see
[GUIDE.md](GUIDE.md). The included code below is a very basic sample that connects to a server and publishes a message using the MQTTv3 synchronous API. More extensive samples of the MQTTv3 and MQTTv5 asynchronous API are in the `org.eclipse.paho.sample.*` modules of [upstream Paho](https://github.com/eclipse-paho/paho.mqtt.java); this repository does not carry them.


```
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttPublishSample {

    public static void main(String[] args) {

        String topic        = "MQTT Examples";
        String content      = "Message from MqttPublishSample";
        int qos             = 2;
        String broker       = "tcp://localhost:1883";
        String clientId     = "JavaSample";
        MemoryPersistence persistence = new MemoryPersistence();

        try {
            MqttClient sampleClient = new MqttClient(broker, clientId, persistence);
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            System.out.println("Connecting to broker: "+broker);
            sampleClient.connect(connOpts);
            System.out.println("Connected");
            System.out.println("Publishing message: "+content);
            MqttMessage message = new MqttMessage(content.getBytes());
            message.setQos(qos);
            sampleClient.publish(topic, message);
            System.out.println("Message published");
            sampleClient.disconnect();
            System.out.println("Disconnected");
            System.exit(0);
        } catch(MqttException me) {
            System.out.println("reason "+me.getReasonCode());
            System.out.println("msg "+me.getMessage());
            System.out.println("loc "+me.getLocalizedMessage());
            System.out.println("cause "+me.getCause());
            System.out.println("excep "+me);
            me.printStackTrace();
        }
    }
}
```
