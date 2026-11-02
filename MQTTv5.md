# Eclipse Paho Java Client for MQTTv5

The v5 client is build on the same foundations as the v3 client is, however it is being heavily refactored using lessons learnt from the v3 client and feedback from the community. Like v3 client, the Paho Java Client provides two APIs: MqttAsyncClient provides a fully asychronous API where completion of activities is notified via registered callbacks. MqttClient is a synchronous wrapper around MqttAsyncClient where functions appear synchronous to the application.

## Using the Paho Java MQTTv5 Client

### Downloading and building

This Gecko build is published as `org.geckoprojects.paho:org.eclipse.paho.mqttv5.client`; see the [README](README.md)
for the Maven coordinates and for building and testing with `./gradlew`.

## Documentation
Reference documentation is online at: [http://www.eclipse.org/paho/files/javadoc/index.html](http://www.eclipse.org/paho/files/javadoc/index.html)

Log and Debug in the Java Client: [https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client](https://wiki.eclipse.org/Paho/Log_and_Debug_in_the_Java_client)

## Getting Started

The included code below is a very basic sample that connects to a server and publishes a message using the MqttClient asynchronous API. More extensive samples are in the `org.eclipse.paho.sample.*` modules of [upstream Paho](https://github.com/eclipse-paho/paho.mqtt.java).


```
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

public class MqttPublishSample {

    public static void main(String[] args) {

        String topic        = "MQTT Examples";
        String content      = "Message from MqttPublishSample";
        int qos             = 2;
        String broker       = "tcp://localhost:1883";
        String clientId     = "JavaSample";
        MemoryPersistence persistence = new MemoryPersistence();

        try {
            MqttConnectionOptions connOpts = new MqttConnectionOptions();
            connOpts.setCleanStart(false);
            MqttAsyncClient sampleClient = new MqttAsyncClient(broker, clientId, persistence);
            System.out.println("Connecting to broker: " + broker);
            IMqttToken token = sampleClient.connect(connOpts);
            token.waitForCompletion();
            System.out.println("Connected");
            System.out.println("Publishing message: "+content);
            MqttMessage message = new MqttMessage(content.getBytes());
            message.setQos(qos);
            token = sampleClient.publish(topic, message);
            token.waitForCompletion();
            System.out.println("Disconnected");
            System.out.println("Close client.");
            sampleClient.close();
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

