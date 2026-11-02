package org.eclipse.paho.client.mqttv3.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.internal.NetworkModule;
import org.eclipse.paho.client.mqttv3.internal.NetworkModuleService;
import org.eclipse.paho.client.mqttv3.internal.TCPNetworkModule;
import org.junit.Test;

public class NetworkModuleServiceTest {

	@Test
	public void testValidateURI() {
		NetworkModuleService.validateURI("tcp://host_literal:1883");
		NetworkModuleService.validateURI("ssl://host_literal:8883");
		NetworkModuleService.validateURI("ws://host_literal:80/path/to/ws");
		NetworkModuleService.validateURI("wss://host_literal:443/path/to/ws");
	}

	public void failEmptyUri() {
		try {
			NetworkModuleService.validateURI("");
			fail("Must fail: Empty URI");
		} catch (IllegalArgumentException e) {
			assertEquals("missing scheme in broker URI: ", e.getMessage());
		}
	}

	@Test
	public void failInvalidUri() {
		try {
			NetworkModuleService.validateURI("no URI at all");
			fail("Must fail: Can't parse string to URI");
		} catch (IllegalArgumentException e) {
			assertEquals("Can't parse string to URI \"no URI at all\"", e.getMessage());
		}
	}

	@Test
	public void failWithPathOnTcpUri() {
		try {
			NetworkModuleService.validateURI("tcp://host_literal:1883/somePath");
			fail("Must fail: URI path must be empty");
		} catch (IllegalArgumentException e) {
			assertEquals("URI path must be empty \"tcp://host_literal:1883/somePath\"", e.getMessage());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void failWithPathOnSslUri() {
		NetworkModuleService.validateURI("ssl://host_literal:1883/somePath");
	}

	@Test
	public void failWithUnsuppurtedSchemeUri() {
		try {
			NetworkModuleService.validateURI("unknown://host_literal:1883");
			fail("Must fail: Unknow scheme");
		} catch (IllegalArgumentException e) {
			assertEquals("no NetworkModule installed for scheme \"unknown\" of URI \"unknown://host_literal:1883\"", e
					.getMessage());
		}
	}

	/**
	 * Test for URI parsing with '_' in hostname.
	 */
	@Test
	public void testRFC3986Authority() throws URISyntaxException {
		URI uri = new URI("tcp://user:password@some_host:666/some_path");
		/*
		 * If the following asserts trigger, the Java URI class parses RFC 3986 host names itself and
		 * the fallback of NetworkModuleService.getHost/getPort/getUserInfo is no longer used.
		 */
		assertNull("fallback no longer necessary?", uri.getUserInfo());
		assertNull("fallback no longer necessary?", uri.getHost());
		assertEquals("fallback no longer necessary?", -1, uri.getPort());

		assertEquals("wrong user info", "user:password", NetworkModuleService.getUserInfo(uri));
		assertEquals("wrong hostname", "some_host", NetworkModuleService.getHost(uri));
		assertEquals("wrong port", 666, NetworkModuleService.getPort(uri));

		URI plain = new URI("tcp://user@localhost:1883");
		assertEquals("user", NetworkModuleService.getUserInfo(plain));
		assertEquals("localhost", NetworkModuleService.getHost(plain));
		assertEquals(1883, NetworkModuleService.getPort(plain));
		assertEquals(-1, NetworkModuleService.getPort(new URI("tcp://localhost")));
	}

	@Test
	public void testCreateInstanceWithUnderscoreHost() throws MqttException {
		// Upstream patched the private fields of java.net.URI for such host names, which fails on
		// Java 16 and later.
		String brokerUri = "tcp://some_host:666";

		NetworkModule result = NetworkModuleService.createInstance(brokerUri, new MqttConnectOptions(), "");

		assertTrue(result instanceof TCPNetworkModule);
		assertEquals(brokerUri, result.getServerURI());
	}

	@Test
	public void testCreateInstance() throws MqttException {
		String brokerUri = "tcp://localhost:666";
		MqttConnectOptions options = new MqttConnectOptions();
		int conTimeout = 234;
		options.setConnectionTimeout(conTimeout);
		String clientId = "";

		NetworkModule result = NetworkModuleService.createInstance(brokerUri, options, clientId);

		assertTrue(result instanceof TCPNetworkModule);
		assertEquals(brokerUri, result.getServerURI());
	}
}
